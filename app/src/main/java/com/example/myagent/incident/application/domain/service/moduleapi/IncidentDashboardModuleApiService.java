package com.example.myagent.incident.application.domain.service.moduleapi;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.dashboard.IncidentDashboardView;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase;
import com.example.myagent.incident.application.port.in.ManageHotfixUseCase;
import com.example.myagent.incident.application.port.in.QueryHotfixUseCase;
import com.example.myagent.incident.application.port.in.QueryIncidentDashboardUseCase;
import com.example.myagent.incident.application.port.in.RefineCandidateUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import com.example.myagent.orchestrator.IncidentDashboardGateway;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IncidentDashboardModuleApiService implements IncidentDashboardGateway {
    private final QueryIncidentDashboardUseCase dashboardUseCase;
    private final SelectCandidateUseCase selectCandidateUseCase;
    private final AnalyzeIncidentUseCase analyzeIncidentUseCase;
    private final ManageHotfixUseCase manageHotfixUseCase;
    private final QueryHotfixUseCase queryHotfixUseCase;
    private final RefineCandidateUseCase refineCandidateUseCase;

    public IncidentDashboardModuleApiService(
        QueryIncidentDashboardUseCase dashboardUseCase,
        SelectCandidateUseCase selectCandidateUseCase,
        AnalyzeIncidentUseCase analyzeIncidentUseCase,
        ManageHotfixUseCase manageHotfixUseCase,
        QueryHotfixUseCase queryHotfixUseCase,
        RefineCandidateUseCase refineCandidateUseCase
    ) {
        this.dashboardUseCase = dashboardUseCase;
        this.selectCandidateUseCase = selectCandidateUseCase;
        this.analyzeIncidentUseCase = analyzeIncidentUseCase;
        this.manageHotfixUseCase = manageHotfixUseCase;
        this.queryHotfixUseCase = queryHotfixUseCase;
        this.refineCandidateUseCase = refineCandidateUseCase;
    }

    @Override
    public List<FailedPullRequest> failedPullRequests() {
        return dashboardUseCase.getFailedPullRequests().stream()
            .map(this::failedPullRequest)
            .toList();
    }

    @Override
    public ResourceResult requestJenkinsAnalysis(JenkinsAnalysisCommand command) {
        var session = analyzeIncidentUseCase.analyzeJenkins(
            new AnalyzeIncidentUseCase.AnalysisCommand<>(
                new AnalysisRequest.Jenkins(
                    command.jobPath(),
                    command.buildNumber(),
                    SourceSpec.pullRequest(command.pullRequestId())
                ),
                command.idempotencyKey()
            )
        );
        String analysisId = session.identity().analysisId();
        return new ResourceResult(
            analysisId,
            session.result().status().name(),
            "/api/v1/analyses/" + analysisId
        );
    }

    @Override
    public List<ObservabilitySignal> observabilitySignals(ObservabilityQuery query) {
        return dashboardUseCase.getObservabilitySignals(
            new QueryIncidentDashboardUseCase.ObservabilityQuery(
                query.startAt(),
                query.endAt(),
                AnalysisRequest.Environment.valueOf(query.environment())
            )
        ).stream().map(this::observabilitySignal).toList();
    }

    @Override
    public ResourceResult requestObservabilityAnalysis(ObservabilityAnalysisCommand command) {
        var observation = command.observation();
        var session = analyzeIncidentUseCase.analyzeObservability(
            new AnalyzeIncidentUseCase.AnalysisCommand<>(
                new AnalysisRequest.Observability(
                    new AnalysisRequest.TimeRange(
                        observation.startAt(),
                        observation.endAt()
                    ),
                    AnalysisRequest.Environment.valueOf(observation.environment()),
                    source(command.source())
                ),
                command.idempotencyKey()
            )
        );
        String analysisId = session.identity().analysisId();
        return new ResourceResult(
            analysisId,
            session.result().status().name(),
            "/api/v1/analyses/" + analysisId
        );
    }

    @Override
    public List<HotfixProgress> hotfixProgresses() {
        return dashboardUseCase.getHotfixProgresses().stream()
            .map(this::hotfixProgress)
            .toList();
    }

    @Override
    public List<StoredAnalysis> recentAnalyses() {
        return dashboardUseCase.getRecentAnalyses().stream()
            .map(this::storedAnalysis)
            .toList();
    }

    @Override
    public Analysis analysis(String analysisId) {
        var analysis = dashboardUseCase.getAnalysis(analysisId);
        return new Analysis(
            new AnalysisIdentity(analysis.identity().analysisId(), analysis.identity().version()),
            analysis.status(),
            analysis.candidates().stream().map(this::candidate).toList(),
            analysis.failureReason()
        );
    }

    @Override
    public Analysis refineCandidate(RefinementCommand command) {
        refineCandidateUseCase.refine(new RefineCandidateUseCase.RefinementCommand(
            command.analysisId(),
            command.analysisVersion(),
            command.candidateId()
        ));
        return analysis(command.analysisId());
    }

    @Override
    public ResourceResult selectCandidate(SelectionCommand command) {
        var resource = selectCandidateUseCase.select(
            new SelectCandidateUseCase.SelectionCommand(
                command.analysisId(),
                command.candidateId(),
                command.analysisVersion(),
                command.idempotencyKey()
            )
        );
        return resourceResult(resource);
    }

    @Override
    public ResourceResult restartHotfix(RestartCommand command) {
        var resource = manageHotfixUseCase.restart(new ManageHotfixUseCase.RestartCommand(
            command.hotfixId(),
            command.idempotencyKey()
        ));
        return resourceResult(resource);
    }

    @Override
    public ResourceResult publishHumanReviewBranch(String hotfixId) {
        return resourceResult(manageHotfixUseCase.publishHumanReviewBranch(hotfixId));
    }

    @Override
    public ResourceResult verifyHumanChanges(String hotfixId) {
        return resourceResult(manageHotfixUseCase.verifyHumanChanges(hotfixId));
    }

    @Override
    public HotfixProgress refreshHotfixCi(String hotfixId) {
        queryHotfixUseCase.refreshCiStatus(hotfixId);
        return dashboardUseCase.getHotfixProgresses().stream()
            .filter(progress -> progress.identity().hotfixId().equals(hotfixId))
            .findFirst()
            .map(this::hotfixProgress)
            .orElseThrow();
    }

    @Override
    public void cancelAndDeleteHotfix(String hotfixId) {
        manageHotfixUseCase.cancelAndDeleteHotfix(hotfixId);
    }

    @Override
    public void cancelAndDeleteWorkflow(String analysisId) {
        manageHotfixUseCase.cancelAndDeleteWorkflow(analysisId);
    }

    private ResourceResult resourceResult(HotfixResource resource) {
        String hotfixId = resource.identity().hotfixId();
        return new ResourceResult(
            hotfixId,
            resource.progress().status().name(),
            "/api/v1/hotfixes/" + hotfixId
        );
    }

    private SourceSpec source(Source source) {
        return "BRANCH".equals(source.type())
            ? SourceSpec.branch(source.branchName())
            : SourceSpec.pullRequest(source.pullRequestId());
    }

    private FailedPullRequest failedPullRequest(
        IncidentDashboardView.FailedPullRequest view
    ) {
        return new FailedPullRequest(
            new PullRequestReference(view.pullRequest().number(), view.pullRequest().url()),
            new BranchReference(view.branch().name(), view.branch().commit()),
            new BuildReference(
                view.build().jobPath(),
                view.build().number(),
                view.build().result(),
                view.build().timestamp(),
                view.build().url()
            )
        );
    }

    private ObservabilitySignal observabilitySignal(
        IncidentDashboardView.ObservabilitySignal view
    ) {
        return new ObservabilitySignal(
            view.type().name(),
            view.title(),
            view.summary(),
            view.occurredAt(),
            new SignalReference(
                view.reference().traceId(),
                view.reference().technicalDetail(),
                view.reference().linkLabel(),
                view.reference().url()
            )
        );
    }

    private HotfixProgress hotfixProgress(IncidentDashboardView.HotfixProgress view) {
        return new HotfixProgress(
            new Identity(
                view.identity().hotfixId(),
                view.identity().analysisId(),
                view.identity().candidateId()
            ),
            new Progress(
                view.progress().status(),
                view.progress().branchName(),
                new StageState(
                    view.progress().stageState().currentStep(),
                    view.progress().stageState().totalSteps(),
                    view.progress().stageState().stage(),
                    view.progress().stageState().message(),
                    new StageExecution(
                        view.progress().stageState().startedAt(),
                        view.progress().stageState().pipelineStages().stream()
                            .map(stage -> new PipelineStage(
                                stage.name(),
                                stage.status(),
                                stage.durationMillis(),
                                stage.detail()
                            ))
                            .toList()
                    )
                ),
                view.progress().failure() == null ? null : new FailureDetail(
                    view.progress().failure().stage(),
                    view.progress().failure().code(),
                    view.progress().failure().message(),
                    view.progress().failure().humanFixAvailable()
                ),
                view.progress().verifications().stream()
                    .map(verification -> new VerificationDetail(
                        verification.name(),
                        verification.exitCode(),
                        verification.required(),
                        verification.summary()
                    ))
                    .toList()
            ),
            new Links(
                view.links().reviewBranchUrl(),
                view.links().draftPullRequestUrl(),
                view.links().ciBuildUrl()
            )
        );
    }

    private Candidate candidate(IncidentDashboardView.Candidate view) {
        return new Candidate(
            view.candidateId(),
            view.title(),
            view.rootCause(),
            view.confidence(),
            view.eligibility(),
            view.refinement() == null ? null : new Refinement(
                view.refinement().status(),
                view.refinement().failureReason()
            )
        );
    }

    private StoredAnalysis storedAnalysis(IncidentDashboardView.StoredAnalysis view) {
        return new StoredAnalysis(
            analysisView(view.analysis()),
            new AnalysisSource(
                view.source().type(),
                view.source().reference(),
                view.source().branch(),
                view.source().commit()
            ),
            view.createdAt()
        );
    }

    private Analysis analysisView(IncidentDashboardView.Analysis view) {
        return new Analysis(
            new AnalysisIdentity(view.identity().analysisId(), view.identity().version()),
            view.status(),
            view.candidates().stream().map(this::candidate).toList(),
            view.failureReason()
        );
    }
}
