package com.example.myagent.dashboard.application.port.in;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import java.time.OffsetDateTime;
import java.util.List;

public interface DashboardUseCase {
    List<DashboardView.FailedPullRequest> getFailedPullRequests();

    DashboardView.ExecutionResult requestJenkinsAnalysis(JenkinsAnalysisCommand command);

    List<DashboardView.ObservabilitySignal> getObservabilitySignals(
        ObservabilityCommand command
    );

    DashboardView.ExecutionResult requestObservabilityAnalysis(
        ObservabilityAnalysisCommand command
    );

    List<DashboardView.HotfixProgress> getHotfixProgresses();

    List<DashboardView.WorkflowItem> getWorkflowItems();

    DashboardView.Analysis getAnalysis(String analysisId);

    DashboardView.Analysis refineCandidate(RefinementCommand command);

    DashboardView.InterpretationPreview interpretNaturalLanguage(
        InterpretationCommand command
    );

    DashboardView.ExecutionResult executeNaturalLanguage(ExecutionCommand command);

    DashboardView.ResourceResult selectCandidate(SelectionCommand command);

    DashboardView.ResourceResult restartHotfix(RestartCommand command);

    DashboardView.ResourceResult publishHumanReviewBranch(String hotfixId);

    DashboardView.ResourceResult verifyHumanChanges(String hotfixId);

    DashboardView.HotfixProgress refreshHotfixCi(String hotfixId);

    void cancelAndDeleteHotfix(String hotfixId);

    void cancelAndDeleteWorkflow(String analysisId);

    record ObservabilityCommand(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String environment
    ) {
    }

    record ObservabilityAnalysisCommand(
        ObservabilityCommand observation,
        SourceCommand source,
        String idempotencyKey
    ) {
    }

    record SourceCommand(String type, String branchName, Long pullRequestId) {
    }

    record JenkinsAnalysisCommand(
        String jobPath,
        long buildNumber,
        long pullRequestId,
        String idempotencyKey
    ) {
    }

    record InterpretationCommand(String text, String idempotencyKey) {
    }

    record ExecutionCommand(
        String interpretationId,
        long version,
        String commandHash,
        String idempotencyKey
    ) {
    }

    record SelectionCommand(
        String analysisId,
        long analysisVersion,
        String candidateId,
        String idempotencyKey
    ) {
    }

    record RefinementCommand(String analysisId, long analysisVersion, String candidateId) {
    }

    record RestartCommand(String hotfixId, String idempotencyKey) {
    }
}
