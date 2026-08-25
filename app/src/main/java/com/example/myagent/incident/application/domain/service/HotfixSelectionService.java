package com.example.myagent.incident.application.domain.service;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.service.internal.HotfixExecutionRegistry;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    private final HotfixExecutionRegistry executionRegistry;

    public HotfixSelectionService(
        IncidentStatePort statePort,
        SourceRevisionPort sourceRevisionPort,
        HotfixWorkflowPort workflowPort,
        AgentRuntimeProperties runtimeProperties,
        Clock clock,
        TaskExecutor taskExecutor,
        HotfixExecutionRegistry executionRegistry
    ) {
        this.statePort = statePort;
        this.sourceRevisionPort = sourceRevisionPort;
        this.workflowPort = workflowPort;
        this.runtimeProperties = runtimeProperties;
        this.clock = clock;
        this.taskExecutor = taskExecutor;
        this.executionRegistry = executionRegistry;
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
        executionRegistry.submit(
            hotfixId,
            () -> process(envelope, analysis, candidate),
            taskExecutor
        );
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
            hotfixId,
            update -> saveProgress(envelope, update),
            () -> executionRegistry.isCancelled(hotfixId)
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
        executionRegistry.runIfActive(hotfixId, () -> statePort.saveHotfix(new HotfixEnvelope(
                envelope.schemaVersion(),
                envelope.idempotencyKey(),
                envelope.requestHash(),
                completed
            )).getOrElseThrow(this::failure));
    }

    private void saveProgress(
        HotfixEnvelope envelope,
        HotfixWorkflowPort.ProgressUpdate update
    ) {
        HotfixResource resource = envelope.resource();
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                update.status(),
                update.branchName(),
                new HotfixResource.ExecutionDetail(update.stage(), update.message()),
                null
            ),
            resource.progress().changes(),
            resource.progress().verification()
        );
        executionRegistry.runIfActive(resource.identity().hotfixId(),
            () -> statePort.saveHotfix(new HotfixEnvelope(
                envelope.schemaVersion(),
                envelope.idempotencyKey(),
                envelope.requestHash(),
                new HotfixResource(resource.identity(), progress, resource.publication())
            )).getOrElseThrow(this::failure));
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
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.SELECTED,
                    null,
                    new HotfixResource.ExecutionDetail(
                        HotfixResource.WorkflowStage.WORKSPACE_PREPARATION,
                        "후보 선택이 완료되어 작업을 준비하고 있습니다."
                    ),
                    null
                ),
                HotfixResource.ChangeMetrics.empty(),
                HotfixResource.Verification.empty()
            ),
            HotfixResource.Publication.empty()
        );
    }

    private HotfixResource failed(HotfixResource resource, String reason) {
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                HotfixResource.Status.FAILED,
                resource.progress().branchName(),
                null,
                new HotfixResource.FailureDetail(
                    HotfixResource.WorkflowStage.PATCH_GENERATION,
                    "HOTFIX_EXECUTION_FAILED",
                    reason
                )
            ),
            resource.progress().changes(),
            resource.progress().verification()
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
        Instant configuredExpiry = analysis.snapshot().createdAt().plus(
            runtimeProperties.analysisTtl()
        );
        Instant effectiveExpiry = analysis.snapshot().expiresAt().isAfter(configuredExpiry)
            ? analysis.snapshot().expiresAt() : configuredExpiry;
        if (!clock.instant().isBefore(effectiveExpiry)) {
            throw new IncidentUseCaseException(
                "ANALYSIS_EXPIRED",
                "분석 결과의 설정된 유효기간이 지나 만료되었습니다. 최신 증거로 다시 분석해주세요."
            );
        }
    }

    private void validateCandidate(BugCandidate candidate) {
        if (!candidate.automaticFixReady()) {
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
