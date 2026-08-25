package com.example.myagent.incident.application.domain.service.internal;

import com.example.myagent.global.annotation.InternalService;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import io.vavr.control.Try;
import org.springframework.core.task.TaskExecutor;

@InternalService
public class HotfixRecoveryExecutor {
    private final IncidentStatePort statePort;
    private final HotfixWorkflowPort workflowPort;
    private final HotfixExecutionRegistry executionRegistry;
    private final TaskExecutor taskExecutor;

    public HotfixRecoveryExecutor(
        IncidentStatePort statePort,
        HotfixWorkflowPort workflowPort,
        HotfixExecutionRegistry executionRegistry,
        TaskExecutor taskExecutor
    ) {
        this.statePort = statePort;
        this.workflowPort = workflowPort;
        this.executionRegistry = executionRegistry;
        this.taskExecutor = taskExecutor;
    }

    public boolean isRecoverable(HotfixEnvelope envelope) {
        return switch (envelope.resource().progress().status()) {
            case SELECTED, PATCHING, VERIFYING -> true;
            case NEEDS_HUMAN_REVIEW, DRAFT_PR_CREATED, RESOLVED, FAILED -> false;
        };
    }

    public void recover(HotfixEnvelope envelope) {
        Try.run(() -> {
            RecoveryContext context = context(envelope);
            HotfixEnvelope recovering = markRecovering(context.envelope());
            executionRegistry.submit(
                recovering.resource().identity().hotfixId(),
                () -> process(recovering, context.analysis(), context.candidate()),
                taskExecutor
            );
        }).onFailure(exception -> save(envelope, failed(envelope.resource(), exception)));
    }

    private RecoveryContext context(HotfixEnvelope envelope) {
        AnalysisSession analysis = statePort.findAnalysis(
            envelope.resource().identity().analysisId()
        ).getOrElseThrow(this::failure)
            .orElseThrow(() -> notFound("ANALYSIS_NOT_FOUND", "분석 결과를 찾을 수 없습니다."))
            .session();
        BugCandidate candidate = analysis.result().candidates().stream()
            .filter(item -> item.identity().candidateId().equals(
                envelope.resource().identity().candidateId()
            ))
            .findFirst()
            .orElseThrow(() -> notFound("CANDIDATE_NOT_FOUND", "원인 후보를 찾을 수 없습니다."));
        return new RecoveryContext(envelope, analysis, candidate);
    }

    private HotfixEnvelope markRecovering(HotfixEnvelope envelope) {
        HotfixResource current = envelope.resource();
        var activity = current.progress().activity();
        var stage = activity == null
            ? HotfixResource.WorkflowStage.WORKSPACE_PREPARATION : activity.stage();
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                current.progress().status(),
                current.progress().branchName(),
                new HotfixResource.ExecutionDetail(
                    stage,
                    "서버 재기동을 감지해 현재 단계를 안전하게 다시 실행하고 있습니다."
                ),
                null
            ),
            current.progress().changes(),
            current.progress().verification()
        );
        HotfixResource recovering = new HotfixResource(
            current.identity(),
            progress,
            current.publication()
        );
        save(envelope, recovering);
        return replace(envelope, recovering);
    }

    private void process(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
        String hotfixId = envelope.resource().identity().hotfixId();
        HotfixResource completed = Try.of(() -> execute(envelope, analysis, candidate))
            .getOrElseGet(exception -> failed(envelope.resource(), exception));
        executionRegistry.runIfActive(hotfixId, () -> save(envelope, completed));
    }

    private HotfixResource execute(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
        HotfixResource current = envelope.resource();
        String hotfixId = current.identity().hotfixId();
        var result = current.publication().reviewBranchUrl() == null
            ? workflowPort.execute(
                analysis,
                candidate,
                hotfixId,
                update -> saveProgress(envelope, update),
                () -> executionRegistry.isCancelled(hotfixId)
            )
            : workflowPort.verifyHumanChanges(
                analysis,
                candidate,
                current,
                update -> saveProgress(envelope, update),
                () -> executionRegistry.isCancelled(hotfixId)
            );
        return result.fold(
            incidentFailure -> failed(current, incidentFailure),
            resource -> resource
        );
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

    private HotfixResource failed(HotfixResource current, Throwable exception) {
        return failed(current, new IncidentFailure(
            "HOTFIX_RECOVERY_FAILED",
            "서버 재기동 후 핫픽스 작업을 복구하지 못했습니다. "
                + exception.getClass().getSimpleName()
        ));
    }

    private HotfixResource failed(HotfixResource current, IncidentFailure incidentFailure) {
        var activity = current.progress().activity();
        var stage = activity == null
            ? HotfixResource.WorkflowStage.WORKSPACE_PREPARATION : activity.stage();
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                current.progress().branchName(),
                null,
                new HotfixResource.FailureDetail(
                    stage,
                    incidentFailure.code(),
                    incidentFailure.message()
                )
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

    private record RecoveryContext(
        HotfixEnvelope envelope,
        AnalysisSession analysis,
        BugCandidate candidate
    ) {
    }
}
