package com.example.myagent.incident.application.domain.service.internal;

import com.example.myagent.global.annotation.InternalService;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.dashboard.IncidentDashboardView;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.ObservabilityDashboardPort;
import java.util.List;

@InternalService
public class IncidentDashboardAssembler {
    private static final int TOTAL_HOTFIX_STEPS = 4;

    public List<IncidentDashboardView.ObservabilitySignal> observabilitySignals(
        List<ObservabilityDashboardPort.Signal> signals
    ) {
        return signals.stream().map(this::observabilitySignal).toList();
    }

    public List<IncidentDashboardView.HotfixProgress> hotfixProgresses(
        List<IncidentStatePort.HotfixEnvelope> envelopes
    ) {
        return envelopes.stream()
            .map(this::hotfixProgress)
            .toList();
    }

    public IncidentDashboardView.Analysis analysis(AnalysisSession session) {
        var candidates = session.result().candidates().stream()
            .map(candidate -> new IncidentDashboardView.Candidate(
                candidate.identity().candidateId(),
                candidate.identity().title(),
                candidate.identity().rootCause(),
                candidate.identity().confidence(),
                candidate.automaticFixReady()
                    ? BugCandidate.Eligibility.ELIGIBLE.name()
                    : restrictedEligibility(candidate)
            ))
            .toList();
        return new IncidentDashboardView.Analysis(
            new IncidentDashboardView.AnalysisIdentity(
                session.identity().analysisId(),
                session.identity().version()
            ),
            session.result().status().name(),
            candidates,
            session.result().failureReason()
        );
    }

    private String restrictedEligibility(BugCandidate candidate) {
        return candidate.identity().eligibility() == BugCandidate.Eligibility.HUMAN_ONLY
            ? BugCandidate.Eligibility.HUMAN_ONLY.name()
            : BugCandidate.Eligibility.INSUFFICIENT_EVIDENCE.name();
    }

    public IncidentDashboardView.StoredAnalysis storedAnalysis(
        IncidentStatePort.AnalysisEnvelope envelope
    ) {
        var session = envelope.session();
        var source = session.snapshot().source();
        var revision = session.snapshot().sourceRevision();
        String reference = source.type() == SourceSpec.Type.PULL_REQUEST
            ? "PR-" + source.pullRequestId() : source.branchName();
        return new IncidentDashboardView.StoredAnalysis(
            analysis(session),
            new IncidentDashboardView.AnalysisSource(
                source.type().name(),
                reference,
                revision == null ? reference : revision.destinationBranch(),
                revision == null ? null : revision.commit()
            ),
            session.snapshot().createdAt()
        );
    }

    private IncidentDashboardView.ObservabilitySignal observabilitySignal(
        ObservabilityDashboardPort.Signal signal
    ) {
        return new IncidentDashboardView.ObservabilitySignal(
            IncidentDashboardView.SignalType.valueOf(signal.type().name()),
            signal.title(),
            signal.summary(),
            signal.occurredAt(),
            new IncidentDashboardView.SignalReference(
                signal.reference().traceId(),
                signal.reference().technicalDetail(),
                signal.reference().linkLabel(),
                signal.reference().url()
            )
        );
    }

    private IncidentDashboardView.HotfixProgress hotfixProgress(
        IncidentStatePort.HotfixEnvelope envelope
    ) {
        HotfixResource resource = envelope.resource();
        return new IncidentDashboardView.HotfixProgress(
            new IncidentDashboardView.Identity(
                resource.identity().hotfixId(),
                resource.identity().analysisId(),
                resource.identity().candidateId()
            ),
            new IncidentDashboardView.Progress(
                resource.progress().status().name(),
                resource.progress().branchName(),
                new IncidentDashboardView.StageState(
                    step(resource),
                    TOTAL_HOTFIX_STEPS,
                    stage(resource),
                    message(resource),
                    new IncidentDashboardView.StageExecution(
                        envelope.updatedAt(),
                        pipelineStages(resource)
                    )
                ),
                failure(resource),
                resource.progress().verification().stages().stream()
                    .map(stage -> new IncidentDashboardView.VerificationDetail(
                        stage.name(),
                        stage.exitCode(),
                        stage.required(),
                        stage.summary()
                    ))
                    .toList()
            ),
            new IncidentDashboardView.Links(
                resource.publication().reviewBranchUrl(),
                resource.publication().draftPullRequestUrl(),
                resource.publication().ciBuildUrl()
            )
        );
    }

    private int step(HotfixResource resource) {
        return switch (resource.progress().status()) {
            case SELECTED -> 1;
            case PATCHING -> 2;
            case VERIFYING -> 3;
            case NEEDS_HUMAN_REVIEW, FAILED -> failureStep(resource);
            case DRAFT_PR_CREATED -> 4;
            case RESOLVED -> 4;
        };
    }

    private String stage(HotfixResource resource) {
        if (resource.progress().failure() != null
            && resource.progress().failure().stage() != null) {
            return resource.progress().failure().stage().name();
        }
        return resource.progress().activity() == null
            ? null : resource.progress().activity().stage().name();
    }

    private int failureStep(HotfixResource resource) {
        if (resource.progress().failure() == null
            || resource.progress().failure().stage() == null) {
            return resource.progress().branchName() == null ? 2 : 3;
        }
        return switch (resource.progress().failure().stage()) {
            case WORKSPACE_PREPARATION, PATCH_GENERATION -> 2;
            case FOCUSED_VERIFICATION, CODE_REVIEW, PARITY_VERIFICATION -> 3;
            case DRAFT_PR_PUBLICATION, CI -> 4;
        };
    }

    private IncidentDashboardView.FailureDetail failure(HotfixResource resource) {
        var failure = resource.progress().failure();
        return failure == null ? null : new IncidentDashboardView.FailureDetail(
            failure.stage() == null
                ? legacyFailureStage(resource) : failure.stage().name(),
            failure.code() == null ? "LEGACY_UNSTRUCTURED_FAILURE" : failure.code(),
            failure.message(),
            resource.progress().branchName() != null
                && failure.stage() != HotfixResource.WorkflowStage.WORKSPACE_PREPARATION
        );
    }

    private String legacyFailureStage(HotfixResource resource) {
        return resource.progress().branchName() == null
            ? HotfixResource.WorkflowStage.WORKSPACE_PREPARATION.name()
            : HotfixResource.WorkflowStage.CODE_REVIEW.name();
    }

    private String message(HotfixResource resource) {
        if (resource.progress().humanReviewReason() != null) {
            return resource.progress().humanReviewReason();
        }
        if (resource.progress().activity() != null
            && resource.progress().activity().message() != null) {
            return resource.progress().activity().message();
        }
        return switch (resource.progress().status()) {
            case SELECTED -> "후보 선택이 완료되어 작업을 준비하고 있습니다.";
            case PATCHING -> "격리 worktree에서 최소 수정안을 적용하고 있습니다.";
            case VERIFYING -> "테스트, review와 Jenkins parity를 검증하고 있습니다.";
            case DRAFT_PR_CREATED -> "Draft PR이 생성되어 Jenkins 결과를 기다리고 있습니다.";
            case RESOLVED -> "Draft PR의 Jenkins 검증이 성공했습니다.";
            case NEEDS_HUMAN_REVIEW -> "사람의 확인이 필요합니다.";
            case FAILED -> "핫픽스 작업이 실패했습니다.";
        };
    }

    private List<IncidentDashboardView.PipelineStage> pipelineStages(HotfixResource resource) {
        if (resource.progress().status() == HotfixResource.Status.DRAFT_PR_CREATED
            || resource.progress().status() == HotfixResource.Status.RESOLVED
            || activeStage(resource) == HotfixResource.WorkflowStage.CI) {
            return resource.publication().ciStages().stream()
                .map(stage -> new IncidentDashboardView.PipelineStage(
                    stage.name(),
                    stage.status(),
                    stage.durationMillis(),
                    stage.detail()
                ))
                .toList();
        }
        return step(resource) < 3 ? List.of() : localPipelineStages(resource);
    }

    private List<IncidentDashboardView.PipelineStage> localPipelineStages(
        HotfixResource resource
    ) {
        List<HotfixResource.WorkflowStage> stages = List.of(
            HotfixResource.WorkflowStage.FOCUSED_VERIFICATION,
            HotfixResource.WorkflowStage.CODE_REVIEW,
            HotfixResource.WorkflowStage.PARITY_VERIFICATION
        );
        HotfixResource.WorkflowStage activeStage = activeStage(resource);
        int activeIndex = stages.indexOf(activeStage);
        return stages.stream()
            .map(stage -> new IncidentDashboardView.PipelineStage(
                localStageLabel(stage),
                localStageStatus(resource, stages.indexOf(stage), activeIndex, stage),
                0,
                stage == activeStage ? message(resource) : null
            ))
            .toList();
    }

    private String localStageStatus(
        HotfixResource resource,
        int stageIndex,
        int activeIndex,
        HotfixResource.WorkflowStage stage
    ) {
        if (resource.progress().failure() != null
            && resource.progress().failure().stage() == stage) {
            return "FAILED";
        }
        if (activeStage(resource) == HotfixResource.WorkflowStage.DRAFT_PR_PUBLICATION
            || resource.progress().status() == HotfixResource.Status.DRAFT_PR_CREATED
            || resource.progress().status() == HotfixResource.Status.RESOLVED) {
            return "SUCCESS";
        }
        if (stageIndex < activeIndex) {
            return "SUCCESS";
        }
        return stageIndex == activeIndex ? "IN_PROGRESS" : "NOT_EXECUTED";
    }

    private HotfixResource.WorkflowStage activeStage(HotfixResource resource) {
        if (resource.progress().failure() != null) {
            return resource.progress().failure().stage();
        }
        return resource.progress().activity() == null
            ? null : resource.progress().activity().stage();
    }

    private String localStageLabel(HotfixResource.WorkflowStage stage) {
        return switch (stage) {
            case FOCUSED_VERIFICATION -> "집중 빌드·테스트";
            case CODE_REVIEW -> "AI 코드 검토";
            case PARITY_VERIFICATION -> "Jenkins 동등성 검증";
            default -> stage.name();
        };
    }
}
