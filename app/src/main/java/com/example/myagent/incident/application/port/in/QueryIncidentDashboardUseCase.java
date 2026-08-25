package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.dashboard.IncidentDashboardView;
import java.time.OffsetDateTime;
import java.util.List;

public interface QueryIncidentDashboardUseCase {
    List<IncidentDashboardView.FailedPullRequest> getFailedPullRequests();

    List<IncidentDashboardView.ObservabilitySignal> getObservabilitySignals(
        ObservabilityQuery query
    );

    List<IncidentDashboardView.HotfixProgress> getHotfixProgresses();

    List<IncidentDashboardView.StoredAnalysis> getRecentAnalyses();

    IncidentDashboardView.Analysis getAnalysis(String analysisId);

    record ObservabilityQuery(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        AnalysisRequest.Environment environment
    ) {
    }
}
