package com.example.myagent.dashboard.adapter.out.module;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.dashboard.application.port.out.DashboardFailure;
import com.example.myagent.dashboard.application.port.out.IncidentDashboardPort;
import com.example.myagent.orchestrator.IncidentDashboardGateway;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.List;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class IncidentDashboardModuleAdapter implements IncidentDashboardPort {
    private final IncidentDashboardGateway gateway;

    public IncidentDashboardModuleAdapter(IncidentDashboardGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Either<DashboardFailure, List<DashboardView.FailedPullRequest>> failedPullRequests() {
        return Try.of(() -> gateway.failedPullRequests().stream()
            .map(this::failedPullRequest)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ExecutionResult> requestJenkinsAnalysis(
        JenkinsAnalysisCommand command
    ) {
        return Try.of(() -> {
            var result = gateway.requestJenkinsAnalysis(
                new IncidentDashboardGateway.JenkinsAnalysisCommand(
                    command.jobPath(),
                    command.buildNumber(),
                    command.pullRequestId(),
                    command.idempotencyKey()
                )
            );
            return new DashboardView.ExecutionResult(
                result.resourceId(),
                result.status(),
                result.statusUrl(),
                List.of()
            );
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, List<DashboardView.ObservabilitySignal>> observabilitySignals(
        ObservabilityQuery query
    ) {
        return Try.of(() -> gateway.observabilitySignals(
            new IncidentDashboardGateway.ObservabilityQuery(
                query.startAt(),
                query.endAt(),
                query.environment()
            )
        ).stream().map(this::observabilitySignal).toList())
            .toEither()
            .mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ExecutionResult> requestObservabilityAnalysis(
        ObservabilityAnalysisCommand command
    ) {
        return Try.of(() -> {
            var observation = command.observation();
            var source = command.source();
            var result = gateway.requestObservabilityAnalysis(
                new IncidentDashboardGateway.ObservabilityAnalysisCommand(
                    new IncidentDashboardGateway.ObservabilityQuery(
                        observation.startAt(),
                        observation.endAt(),
                        observation.environment()
                    ),
                    new IncidentDashboardGateway.Source(
                        source.type(),
                        source.branchName(),
                        source.pullRequestId()
                    ),
                    command.idempotencyKey()
                )
            );
            return new DashboardView.ExecutionResult(
                result.resourceId(),
                result.status(),
                result.statusUrl(),
                List.of()
            );
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, List<DashboardView.HotfixProgress>> hotfixProgresses() {
        return Try.of(() -> gateway.hotfixProgresses().stream()
            .map(this::hotfixProgress)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, List<DashboardView.StoredAnalysis>> recentAnalyses() {
        return Try.of(() -> gateway.recentAnalyses().stream()
            .map(this::storedAnalysis)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.Analysis> analysis(String analysisId) {
        return Try.of(() -> toAnalysis(gateway.analysis(analysisId)))
            .toEither()
            .mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.Analysis> refineCandidate(
        RefinementCommand command
    ) {
        return Try.of(() -> toAnalysis(gateway.refineCandidate(
            new IncidentDashboardGateway.RefinementCommand(
                command.analysisId(),
                command.analysisVersion(),
                command.candidateId()
            )
        ))).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ResourceResult> selectCandidate(
        SelectionCommand command
    ) {
        return Try.of(() -> {
            var result = gateway.selectCandidate(
                new IncidentDashboardGateway.SelectionCommand(
                    command.analysisId(),
                    command.analysisVersion(),
                    command.candidateId(),
                    command.idempotencyKey(),
                    command.patchInstruction()
                )
            );
            return new DashboardView.ResourceResult(
                result.resourceId(),
                result.status(),
                result.statusUrl()
            );
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ResourceResult> restartHotfix(
        RestartCommand command
    ) {
        return Try.of(() -> resourceResult(gateway.restartHotfix(
            new IncidentDashboardGateway.RestartCommand(
                command.hotfixId(),
                command.idempotencyKey()
            )
        ))).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ResourceResult> publishHumanReviewBranch(
        String hotfixId
    ) {
        return Try.of(() -> resourceResult(gateway.publishHumanReviewBranch(hotfixId)))
            .toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ResourceResult> verifyHumanChanges(
        String hotfixId
    ) {
        return Try.of(() -> resourceResult(gateway.verifyHumanChanges(hotfixId)))
            .toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.HotfixProgress> refreshHotfixCi(
        String hotfixId
    ) {
        return Try.of(() -> hotfixProgress(gateway.refreshHotfixCi(hotfixId)))
            .toEither()
            .mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, Boolean> cancelAndDeleteHotfix(String hotfixId) {
        return Try.of(() -> {
            gateway.cancelAndDeleteHotfix(hotfixId);
            return true;
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, Boolean> cancelAndDeleteWorkflow(String analysisId) {
        return Try.of(() -> {
            gateway.cancelAndDeleteWorkflow(analysisId);
            return true;
        }).toEither().mapLeft(this::failure);
    }

    private DashboardView.FailedPullRequest failedPullRequest(
        IncidentDashboardGateway.FailedPullRequest view
    ) {
        return new DashboardView.FailedPullRequest(
            new DashboardView.PullRequestReference(
                view.pullRequest().number(),
                view.pullRequest().url()
            ),
            new DashboardView.BranchReference(view.branch().name(), view.branch().commit()),
            new DashboardView.BuildReference(
                view.build().jobPath(),
                view.build().number(),
                view.build().result(),
                view.build().timestamp(),
                view.build().url()
            )
        );
    }

    private DashboardView.ResourceResult resourceResult(
        IncidentDashboardGateway.ResourceResult result
    ) {
        return new DashboardView.ResourceResult(
            result.resourceId(),
            result.status(),
            result.statusUrl()
        );
    }

    private DashboardView.ObservabilitySignal observabilitySignal(
        IncidentDashboardGateway.ObservabilitySignal view
    ) {
        return new DashboardView.ObservabilitySignal(
            view.type(),
            view.title(),
            view.summary(),
            view.occurredAt(),
            new DashboardView.SignalReference(
                view.reference().traceId(),
                view.reference().technicalDetail(),
                view.reference().linkLabel(),
                view.reference().url()
            )
        );
    }

    private DashboardView.HotfixProgress hotfixProgress(
        IncidentDashboardGateway.HotfixProgress view
    ) {
        return new DashboardView.HotfixProgress(
            new DashboardView.Identity(
                view.identity().hotfixId(),
                view.identity().analysisId(),
                view.identity().candidateId()
            ),
            new DashboardView.Progress(
                view.progress().status(),
                view.progress().branchName(),
                new DashboardView.StageState(
                    view.progress().stageState().currentStep(),
                    view.progress().stageState().totalSteps(),
                    view.progress().stageState().stage(),
                    view.progress().stageState().message(),
                    new DashboardView.StageExecution(
                        view.progress().stageState().startedAt(),
                        view.progress().stageState().pipelineStages().stream()
                            .map(stage -> new DashboardView.PipelineStage(
                                stage.name(),
                                stage.status(),
                                stage.durationMillis(),
                                stage.detail()
                            ))
                            .toList()
                    )
                ),
                view.progress().failure() == null ? null : new DashboardView.FailureDetail(
                    view.progress().failure().stage(),
                    view.progress().failure().code(),
                    view.progress().failure().message(),
                    view.progress().failure().humanFixAvailable()
                ),
                view.progress().verifications().stream()
                    .map(verification -> new DashboardView.VerificationDetail(
                        verification.name(),
                        verification.exitCode(),
                        verification.required(),
                        verification.summary()
                    ))
                    .toList()
            ),
            new DashboardView.Links(
                view.links().reviewBranchUrl(),
                view.links().draftPullRequestUrl(),
                view.links().ciBuildUrl()
            )
        );
    }

    private DashboardView.Analysis toAnalysis(IncidentDashboardGateway.Analysis view) {
        return new DashboardView.Analysis(
            new DashboardView.AnalysisIdentity(
                view.identity().analysisId(),
                view.identity().version()
            ),
            view.status(),
            view.candidates().stream().map(this::candidate).toList(),
            view.failureReason()
        );
    }

    private DashboardView.Candidate candidate(IncidentDashboardGateway.Candidate view) {
        return new DashboardView.Candidate(
            view.candidateId(),
            view.title(),
            view.rootCause(),
            view.confidence(),
            view.eligibility(),
            view.refinement() == null ? null : new DashboardView.Refinement(
                view.refinement().status(),
                view.refinement().failureReason()
            )
        );
    }

    private DashboardView.StoredAnalysis storedAnalysis(
        IncidentDashboardGateway.StoredAnalysis view
    ) {
        return new DashboardView.StoredAnalysis(
            toAnalysis(view.analysis()),
            new DashboardView.AnalysisSource(
                view.source().type(),
                view.source().reference(),
                view.source().branch(),
                view.source().commit()
            ),
            view.createdAt()
        );
    }

    private DashboardFailure failure(Throwable throwable) {
        return new DashboardFailure("INCIDENT_DASHBOARD_FAILED", throwable.getMessage());
    }
}
