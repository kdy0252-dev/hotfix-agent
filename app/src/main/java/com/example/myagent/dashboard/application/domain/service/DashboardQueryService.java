package com.example.myagent.dashboard.application.domain.service;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.dashboard.application.domain.service.internal.ConversationPriorityResolver;
import com.example.myagent.dashboard.application.domain.service.internal.DashboardWorkflowAssembler;
import com.example.myagent.dashboard.application.domain.service.internal.NaturalLanguageRequestEnricher;
import com.example.myagent.dashboard.application.port.in.DashboardUseCase;
import com.example.myagent.dashboard.application.port.in.DashboardUseCaseException;
import com.example.myagent.dashboard.application.port.out.DashboardFailure;
import com.example.myagent.dashboard.application.port.out.IncidentDashboardPort;
import com.example.myagent.dashboard.application.port.out.NaturalLanguageDashboardPort;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DashboardQueryService implements DashboardUseCase {
    private final IncidentDashboardPort incidentDashboardPort;
    private final NaturalLanguageDashboardPort naturalLanguageDashboardPort;
    private final DashboardWorkflowAssembler workflowAssembler;
    private final NaturalLanguageRequestEnricher requestEnricher;
    private final ConversationPriorityResolver priorityResolver;

    public DashboardQueryService(
        IncidentDashboardPort incidentDashboardPort,
        NaturalLanguageDashboardPort naturalLanguageDashboardPort,
        DashboardWorkflowAssembler workflowAssembler,
        NaturalLanguageRequestEnricher requestEnricher,
        ConversationPriorityResolver priorityResolver
    ) {
        this.incidentDashboardPort = incidentDashboardPort;
        this.naturalLanguageDashboardPort = naturalLanguageDashboardPort;
        this.workflowAssembler = workflowAssembler;
        this.requestEnricher = requestEnricher;
        this.priorityResolver = priorityResolver;
    }

    @Override
    public List<DashboardView.FailedPullRequest> getFailedPullRequests() {
        return incidentDashboardPort.failedPullRequests().getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ExecutionResult requestJenkinsAnalysis(
        JenkinsAnalysisCommand command
    ) {
        return incidentDashboardPort.requestJenkinsAnalysis(
            new IncidentDashboardPort.JenkinsAnalysisCommand(
                command.jobPath(),
                command.buildNumber(),
                command.pullRequestId(),
                command.idempotencyKey()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public List<DashboardView.ObservabilitySignal> getObservabilitySignals(
        ObservabilityCommand command
    ) {
        return incidentDashboardPort.observabilitySignals(
            new IncidentDashboardPort.ObservabilityQuery(
                command.startAt(),
                command.endAt(),
                command.environment()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ExecutionResult requestObservabilityAnalysis(
        ObservabilityAnalysisCommand command
    ) {
        var observation = command.observation();
        var source = command.source();
        return incidentDashboardPort.requestObservabilityAnalysis(
            new IncidentDashboardPort.ObservabilityAnalysisCommand(
                new IncidentDashboardPort.ObservabilityQuery(
                    observation.startAt(),
                    observation.endAt(),
                    observation.environment()
                ),
                new IncidentDashboardPort.Source(
                    source.type(),
                    source.branchName(),
                    source.pullRequestId()
                ),
                command.idempotencyKey()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public List<DashboardView.HotfixProgress> getHotfixProgresses() {
        return incidentDashboardPort.hotfixProgresses().getOrElseThrow(this::failure);
    }

    @Override
    public List<DashboardView.WorkflowItem> getWorkflowItems() {
        return workflowItems();
    }

    private List<DashboardView.WorkflowItem> workflowItems() {
        var analyses = incidentDashboardPort.recentAnalyses().getOrElseThrow(this::failure);
        var hotfixes = incidentDashboardPort.hotfixProgresses().getOrElseThrow(this::failure);
        return workflowAssembler.assemble(analyses, hotfixes);
    }

    @Override
    public Optional<DashboardView.CandidatePriority> getMostUrgentCandidate() {
        return priorityResolver.mostUrgent(workflowItems());
    }

    @Override
    public List<DashboardView.CandidatePriority> getRefinementPriorities() {
        return priorityResolver.refinementPriorities(workflowItems());
    }

    @Override
    public DashboardView.Analysis getAnalysis(String analysisId) {
        return incidentDashboardPort.analysis(analysisId).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.Analysis refineCandidate(RefinementCommand command) {
        return incidentDashboardPort.refineCandidate(
            new IncidentDashboardPort.RefinementCommand(
                command.analysisId(),
                command.analysisVersion(),
                command.candidateId()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.InterpretationPreview interpretNaturalLanguage(
        InterpretationCommand command
    ) {
        String text = command.text();
        if (requestEnricher.needsFailedPullRequestContext(text)) {
            text = requestEnricher.enrich(text, getFailedPullRequests());
        }
        return naturalLanguageDashboardPort.interpret(
            new NaturalLanguageDashboardPort.InterpretationCommand(
                text,
                command.idempotencyKey()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.InterpretationPreview getNaturalLanguageInterpretation(
        String interpretationId
    ) {
        return naturalLanguageDashboardPort.interpretation(interpretationId)
            .getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ExecutionResult executeNaturalLanguage(ExecutionCommand command) {
        return naturalLanguageDashboardPort.execute(
            new NaturalLanguageDashboardPort.ExecutionCommand(
                command.interpretationId(),
                command.version(),
                command.commandHash(),
                command.idempotencyKey()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ResourceResult selectCandidate(SelectionCommand command) {
        return incidentDashboardPort.selectCandidate(
            new IncidentDashboardPort.SelectionCommand(
                command.analysisId(),
                command.analysisVersion(),
                command.candidateId(),
                command.idempotencyKey()
            )
        ).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ResourceResult restartHotfix(RestartCommand command) {
        return incidentDashboardPort.restartHotfix(new IncidentDashboardPort.RestartCommand(
            command.hotfixId(),
            command.idempotencyKey()
        )).getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ResourceResult publishHumanReviewBranch(String hotfixId) {
        return incidentDashboardPort.publishHumanReviewBranch(hotfixId)
            .getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.ResourceResult verifyHumanChanges(String hotfixId) {
        return incidentDashboardPort.verifyHumanChanges(hotfixId)
            .getOrElseThrow(this::failure);
    }

    @Override
    public DashboardView.HotfixProgress refreshHotfixCi(String hotfixId) {
        return incidentDashboardPort.refreshHotfixCi(hotfixId).getOrElseThrow(this::failure);
    }

    @Override
    public void cancelAndDeleteHotfix(String hotfixId) {
        incidentDashboardPort.cancelAndDeleteHotfix(hotfixId).getOrElseThrow(this::failure);
    }

    @Override
    public void cancelAndDeleteWorkflow(String analysisId) {
        incidentDashboardPort.cancelAndDeleteWorkflow(analysisId).getOrElseThrow(this::failure);
    }

    private DashboardUseCaseException failure(DashboardFailure dashboardFailure) {
        return new DashboardUseCaseException(dashboardFailure.code(), dashboardFailure.message());
    }
}
