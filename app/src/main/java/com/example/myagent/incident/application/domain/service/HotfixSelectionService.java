package com.example.myagent.incident.application.domain.service;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.service.support.IncidentRequestHash;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import com.example.myagent.incident.application.port.out.SourceRevisionPort;
import io.vavr.control.Try;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class HotfixSelectionService implements SelectCandidateUseCase {
    private static final int SCHEMA_VERSION = 1;

    private final IncidentStatePort statePort;
    private final SourceRevisionPort sourceRevisionPort;
    private final HotfixWorkflowPort workflowPort;
    private final AgentRuntimeProperties runtimeProperties;
    private final Clock clock;
    private final TaskExecutor taskExecutor;
    private final Set<String> activeHotfixes = ConcurrentHashMap.newKeySet();

    public HotfixSelectionService(
        IncidentStatePort statePort,
        SourceRevisionPort sourceRevisionPort,
        HotfixWorkflowPort workflowPort,
        AgentRuntimeProperties runtimeProperties,
        Clock clock,
        TaskExecutor taskExecutor
    ) {
        this.statePort = statePort;
        this.sourceRevisionPort = sourceRevisionPort;
        this.workflowPort = workflowPort;
        this.runtimeProperties = runtimeProperties;
        this.clock = clock;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public synchronized HotfixResource select(SelectionCommand command) {
        requireDraftPrMode();
        String requestHash = IncidentRequestHash.calculate(command);
        var previous = statePort.findHotfixByIdempotencyKey(command.idempotencyKey())
            .getOrElseThrow(this::failure);
        if (previous.isPresent()) {
            HotfixResource replayed = replay(previous.get(), requestHash);
            resumeIfSelected(command, previous.get(), replayed);
            return replayed;
        }
        AnalysisSession analysis = analysis(command.analysisId());
        validateAnalysis(command, analysis);
        BugCandidate candidate = candidate(command.candidateId(), analysis);
        validateCandidate(candidate);
        validateSourceFreshness(analysis);
        String hotfixId = UUID.randomUUID().toString();
        HotfixResource selected = selected(analysis, candidate, hotfixId);
        HotfixEnvelope envelope = new HotfixEnvelope(
            SCHEMA_VERSION,
            command.idempotencyKey(),
            requestHash,
            selected
        );
        HotfixResource saved = statePort.saveHotfix(envelope).getOrElseThrow(this::failure);
        submit(envelope, analysis, candidate);
        return saved;
    }

    private void resumeIfSelected(
        SelectionCommand command,
        HotfixEnvelope envelope,
        HotfixResource resource
    ) {
        if (resource.progress().status() != HotfixResource.Status.SELECTED) {
            return;
        }
        AnalysisSession analysis = analysis(command.analysisId());
        BugCandidate candidate = candidate(command.candidateId(), analysis);
        submit(envelope, analysis, candidate);
    }

    private void submit(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
        String hotfixId = envelope.resource().identity().hotfixId();
        if (!activeHotfixes.add(hotfixId)) {
            return;
        }
        Try.run(() -> taskExecutor.execute(() -> Try.run(
                () -> process(envelope, analysis, candidate)
            ).andFinally(() -> activeHotfixes.remove(hotfixId)).get()))
            .onFailure(exception -> activeHotfixes.remove(hotfixId));
    }

    private void process(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
        String hotfixId = envelope.resource().identity().hotfixId();
        HotfixResource completed = Try.of(() -> workflowPort.execute(
            analysis,
            candidate,
            hotfixId
        ).fold(
            failure -> failed(
                envelope.resource(),
                failure.code() + ": " + failure.message()
            ),
            resource -> resource
        ))
            .getOrElseGet(exception -> failed(
                envelope.resource(),
                "핫픽스 작업을 완료하지 못했습니다."
            ));
        statePort.saveHotfix(new HotfixEnvelope(
            envelope.schemaVersion(),
            envelope.idempotencyKey(),
            envelope.requestHash(),
            completed
        )).getOrElseThrow(this::failure);
    }

    private HotfixResource selected(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId
    ) {
        return new HotfixResource(
            new HotfixResource.Identity(
                hotfixId,
                analysis.identity().analysisId(),
                candidate.identity().candidateId()
            ),
            new HotfixResource.Progress(
                HotfixResource.Status.SELECTED,
                null,
                0,
                0,
                HotfixResource.Verification.empty(),
                null
            ),
            new HotfixResource.Publication(null, null, null)
        );
    }

    private HotfixResource failed(HotfixResource resource, String reason) {
        var progress = new HotfixResource.Progress(
            HotfixResource.Status.FAILED,
            resource.progress().branchName(),
            resource.progress().changedFiles(),
            resource.progress().changedLines(),
            resource.progress().verification(),
            reason
        );
        return new HotfixResource(resource.identity(), progress, resource.publication());
    }

    private AnalysisSession analysis(String analysisId) {
        return statePort.findAnalysis(analysisId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> new IncidentUseCaseException(
                "ANALYSIS_NOT_FOUND",
                "분석 결과를 찾을 수 없습니다."
            )).session();
    }

    private BugCandidate candidate(String candidateId, AnalysisSession analysis) {
        return analysis.result().candidates().stream()
            .filter(candidate -> candidate.identity().candidateId().equals(candidateId))
            .findFirst()
            .orElseThrow(() -> new IncidentUseCaseException(
                "CANDIDATE_NOT_FOUND",
                "분석에 속한 후보를 찾을 수 없습니다."
            ));
    }

    private void validateAnalysis(SelectionCommand command, AnalysisSession analysis) {
        if (analysis.identity().version() != command.analysisVersion()) {
            throw new IncidentUseCaseException("STALE_ANALYSIS", "분석 version이 최신이 아닙니다.");
        }
        if (!clock.instant().isBefore(analysis.snapshot().expiresAt())) {
            throw new IncidentUseCaseException("ANALYSIS_EXPIRED", "분석 결과가 만료되었습니다.");
        }
    }

    private void validateCandidate(BugCandidate candidate) {
        if (candidate.identity().eligibility() != BugCandidate.Eligibility.ELIGIBLE
            || candidate.evidence().sourceLocations().isEmpty()
            || candidate.evidence().evidenceRefs().isEmpty()) {
            throw new IncidentUseCaseException(
                "CANDIDATE_NOT_ELIGIBLE",
                "자동 수정 eligibility 또는 evidence coverage가 부족한 후보입니다."
            );
        }
    }

    private void validateSourceFreshness(AnalysisSession analysis) {
        String currentCommit = sourceRevisionPort.resolve(analysis.snapshot().source())
            .getOrElseThrow(this::failure)
            .commit();
        if (!analysis.snapshot().sourceRevision().commit().equals(currentCommit)) {
            throw new IncidentUseCaseException(
                "STALE_SOURCE",
                "분석 이후 source revision이 변경되었습니다."
            );
        }
    }

    private void requireDraftPrMode() {
        if (runtimeProperties.mode() != AgentRuntimeProperties.Mode.DRAFT_PR) {
            throw new IncidentUseCaseException(
                "REPORT_ONLY_MODE",
                "DRAFT_PR 모드에서만 후보를 선택할 수 있습니다."
            );
        }
    }

    private HotfixResource replay(HotfixEnvelope envelope, String requestHash) {
        if (!envelope.requestHash().equals(requestHash)) {
            throw new IncidentUseCaseException(
                "IDEMPOTENCY_KEY_REUSED",
                "같은 idempotency key에 다른 선택 요청을 사용할 수 없습니다."
            );
        }
        return envelope.resource();
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
