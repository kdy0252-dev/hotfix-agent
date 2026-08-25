package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.service.internal.HotfixExecutionRegistry;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.ManageHotfixUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class HotfixManagementService implements ManageHotfixUseCase {
    private final IncidentStatePort statePort;
    private final SelectCandidateUseCase selectCandidateUseCase;
    private final HotfixExecutionRegistry executionRegistry;
    private final HotfixWorkflowPort workflowPort;
    private final TaskExecutor taskExecutor;

    public HotfixManagementService(
        IncidentStatePort statePort,
        SelectCandidateUseCase selectCandidateUseCase,
        HotfixExecutionRegistry executionRegistry,
        HotfixWorkflowPort workflowPort,
        TaskExecutor taskExecutor
    ) {
        this.statePort = statePort;
        this.selectCandidateUseCase = selectCandidateUseCase;
        this.executionRegistry = executionRegistry;
        this.workflowPort = workflowPort;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public HotfixResource publishHumanReviewBranch(String hotfixId) {
        HotfixContext context = context(hotfixId);
        requireHumanReview(context.envelope().resource());
        HotfixResource published = workflowPort.publishForHumanReview(
            context.analysis(),
            context.candidate(),
            context.envelope().resource()
        ).getOrElseThrow(this::failure);
        return save(context.envelope(), published);
    }

    @Override
    public HotfixResource verifyHumanChanges(String hotfixId) {
        HotfixContext context = context(hotfixId);
        HotfixResource current = context.envelope().resource();
        requireHumanReview(current);
        if (current.publication().reviewBranchUrl() == null) {
            throw new IncidentUseCaseException(
                "HUMAN_REVIEW_BRANCH_NOT_PUBLISHED",
                "사람 수정 commit을 검증하기 전에 검토 branch를 게시해야 합니다."
            );
        }
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                HotfixResource.Status.VERIFYING,
                current.progress().branchName(),
                new HotfixResource.ExecutionDetail(
                    HotfixResource.WorkflowStage.FOCUSED_VERIFICATION,
                    "사람이 push한 commit을 가져오기 위해 검증 작업을 시작합니다."
                ),
                null
            ),
            current.progress().changes(),
            current.progress().verification()
        );
        HotfixResource verifying = save(
            context.envelope(),
            new HotfixResource(current.identity(), progress, current.publication())
        );
        HotfixEnvelope verifyingEnvelope = replace(context.envelope(), verifying);
        executionRegistry.submit(
            hotfixId,
            () -> runHumanVerification(verifyingEnvelope, context.analysis(), context.candidate()),
            taskExecutor
        );
        return verifying;
    }

    @Override
    public HotfixResource restart(RestartCommand command) {
        var previous = hotfix(command.hotfixId());
        var analysis = statePort.findAnalysis(previous.identity().analysisId())
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> notFound("ANALYSIS_NOT_FOUND", "분석 결과를 찾을 수 없습니다."))
            .session();
        return selectCandidateUseCase.select(new SelectCandidateUseCase.SelectionCommand(
            analysis.identity().analysisId(),
            previous.identity().candidateId(),
            analysis.identity().version(),
            command.idempotencyKey()
        ));
    }

    @Override
    public void cancelAndDeleteHotfix(String hotfixId) {
        hotfix(hotfixId);
        executionRegistry.cancel(hotfixId);
        statePort.deleteHotfix(hotfixId).getOrElseThrow(this::failure);
    }

    @Override
    public void cancelAndDeleteWorkflow(String analysisId) {
        var hotfixes = statePort.findAllHotfixes().getOrElseThrow(this::failure).stream()
            .filter(envelope -> envelope.resource().identity().analysisId().equals(analysisId))
            .toList();
        hotfixes.stream()
            .map(envelope -> envelope.resource().identity().hotfixId())
            .forEach(executionRegistry::cancel);
        statePort.deleteWorkflow(analysisId).getOrElseThrow(this::failure);
    }

    private HotfixResource hotfix(String hotfixId) {
        return statePort.findHotfix(hotfixId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> notFound("HOTFIX_NOT_FOUND", "핫픽스 상태를 찾을 수 없습니다."))
            .resource();
    }

    private void requireHumanReview(HotfixResource resource) {
        if (resource.progress().status() != HotfixResource.Status.NEEDS_HUMAN_REVIEW
            && resource.progress().status() != HotfixResource.Status.FAILED) {
            throw new IncidentUseCaseException(
                "HUMAN_REVIEW_NOT_AVAILABLE",
                "사람 수정 흐름은 실패하거나 사람 검토 대기 중인 hotfix에서만 실행할 수 있습니다."
            );
        }
    }

    private HotfixContext context(String hotfixId) {
        HotfixEnvelope envelope = statePort.findHotfix(hotfixId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> notFound("HOTFIX_NOT_FOUND", "핫픽스 상태를 찾을 수 없습니다."));
        AnalysisSession analysis = statePort.findAnalysis(envelope.resource().identity().analysisId())
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> notFound("ANALYSIS_NOT_FOUND", "분석 결과를 찾을 수 없습니다."))
            .session();
        BugCandidate candidate = analysis.result().candidates().stream()
            .filter(item -> item.identity().candidateId().equals(
                envelope.resource().identity().candidateId()
            ))
            .findFirst()
            .orElseThrow(() -> notFound("CANDIDATE_NOT_FOUND", "원인 후보를 찾을 수 없습니다."));
        return new HotfixContext(envelope, analysis, candidate);
    }

    private void runHumanVerification(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
        String hotfixId = envelope.resource().identity().hotfixId();
        HotfixResource completed = workflowPort.verifyHumanChanges(
            analysis,
            candidate,
            envelope.resource(),
            update -> saveProgress(envelope, update),
            () -> executionRegistry.isCancelled(hotfixId)
        ).fold(
            incidentFailure -> failed(envelope.resource(), incidentFailure),
            resource -> resource
        );
        executionRegistry.runIfActive(hotfixId, () -> save(envelope, completed));
    }

    private void saveProgress(HotfixEnvelope envelope, HotfixWorkflowPort.ProgressUpdate update) {
        HotfixResource current = envelope.resource();
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                update.status(),
                update.branchName(),
                new HotfixResource.ExecutionDetail(update.stage(), update.message()),
                null
            ),
            current.progress().changes(),
            current.progress().verification()
        );
        executionRegistry.runIfActive(current.identity().hotfixId(), () -> save(
            envelope,
            new HotfixResource(current.identity(), progress, current.publication())
        ));
    }

    private HotfixResource failed(HotfixResource current, IncidentFailure incidentFailure) {
        var failureDetail = new HotfixResource.FailureDetail(
            HotfixResource.WorkflowStage.FOCUSED_VERIFICATION,
            incidentFailure.code(),
            incidentFailure.message()
        );
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                current.progress().branchName(),
                null,
                failureDetail
            ),
            current.progress().changes(),
            current.progress().verification()
        );
        return new HotfixResource(current.identity(), progress, current.publication());
    }

    private HotfixResource save(HotfixEnvelope envelope, HotfixResource resource) {
        return statePort.saveHotfix(replace(envelope, resource)).getOrElseThrow(this::failure);
    }

    private HotfixEnvelope replace(HotfixEnvelope envelope, HotfixResource resource) {
        return new HotfixEnvelope(
            envelope.schemaVersion(),
            envelope.idempotencyKey(),
            envelope.requestHash(),
            resource
        );
    }

    private IncidentUseCaseException notFound(String code, String message) {
        return new IncidentUseCaseException(code, message);
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }

    private record HotfixContext(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
    }
}
