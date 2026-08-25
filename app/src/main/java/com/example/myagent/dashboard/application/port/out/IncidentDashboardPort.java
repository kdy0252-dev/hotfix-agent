package com.example.myagent.dashboard.application.port.out;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import io.vavr.control.Either;
import java.time.OffsetDateTime;
import java.util.List;

public interface IncidentDashboardPort {
    Either<DashboardFailure, List<DashboardView.FailedPullRequest>> failedPullRequests();

    Either<DashboardFailure, DashboardView.ExecutionResult> requestJenkinsAnalysis(
        JenkinsAnalysisCommand command
    );

    Either<DashboardFailure, List<DashboardView.ObservabilitySignal>> observabilitySignals(
        ObservabilityQuery query
    );

    Either<DashboardFailure, DashboardView.ExecutionResult> requestObservabilityAnalysis(
        ObservabilityAnalysisCommand command
    );

    Either<DashboardFailure, List<DashboardView.HotfixProgress>> hotfixProgresses();

    Either<DashboardFailure, List<DashboardView.StoredAnalysis>> recentAnalyses();

    Either<DashboardFailure, DashboardView.Analysis> analysis(String analysisId);

    Either<DashboardFailure, DashboardView.Analysis> refineCandidate(
        RefinementCommand command
    );

    Either<DashboardFailure, DashboardView.ResourceResult> selectCandidate(
        SelectionCommand command
    );

    Either<DashboardFailure, DashboardView.ResourceResult> restartHotfix(
        RestartCommand command
    );

    Either<DashboardFailure, DashboardView.ResourceResult> publishHumanReviewBranch(
        String hotfixId
    );

    Either<DashboardFailure, DashboardView.ResourceResult> verifyHumanChanges(String hotfixId);

    Either<DashboardFailure, DashboardView.HotfixProgress> refreshHotfixCi(String hotfixId);

    Either<DashboardFailure, Boolean> cancelAndDeleteHotfix(String hotfixId);

    Either<DashboardFailure, Boolean> cancelAndDeleteWorkflow(String analysisId);

    record ObservabilityQuery(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String environment
    ) {
    }

    record ObservabilityAnalysisCommand(
        ObservabilityQuery observation,
        Source source,
        String idempotencyKey
    ) {
    }

    record Source(String type, String branchName, Long pullRequestId) {
    }

    record JenkinsAnalysisCommand(
        String jobPath,
        long buildNumber,
        long pullRequestId,
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
