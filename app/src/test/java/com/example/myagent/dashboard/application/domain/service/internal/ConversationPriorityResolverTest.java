package com.example.myagent.dashboard.application.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationPriorityResolverTest {
    private final ConversationPriorityResolver resolver = new ConversationPriorityResolver();

    @Test
    void selectsTheHighestConfidenceActionableCandidateAsMostUrgent() {
        var lowerConfidence = workflow("analysis-low", "candidate-low", 0.7, "ELIGIBLE");
        var higherConfidence = workflow("analysis-high", "candidate-high", 0.9, "ELIGIBLE");

        var priority = resolver.mostUrgent(List.of(lowerConfidence, higherConfidence));

        assertThat(priority).isPresent();
        assertThat(priority.orElseThrow().candidateWorkflow().candidate().candidateId())
            .isEqualTo("candidate-high");
    }

    @Test
    void ranksLowEvidenceCandidatesBeforeLowConfidenceActionableCandidates() {
        var actionable = workflow("analysis-actionable", "candidate-actionable", 0.7, "ELIGIBLE");
        var insufficient = workflow(
            "analysis-insufficient",
            "candidate-insufficient",
            0.2,
            "INSUFFICIENT_EVIDENCE"
        );

        var priorities = resolver.refinementPriorities(List.of(actionable, insufficient));

        assertThat(priorities).extracting(
            priority -> priority.candidateWorkflow().candidate().candidateId()
        ).containsExactly("candidate-insufficient", "candidate-actionable");
    }

    private DashboardView.WorkflowItem workflow(
        String analysisId,
        String candidateId,
        double confidence,
        String eligibility
    ) {
        var candidate = new DashboardView.Candidate(
            candidateId,
            "원인 후보",
            "분석 결과",
            confidence,
            eligibility,
            null
        );
        return new DashboardView.WorkflowItem(
            new DashboardView.StoredAnalysis(
                new DashboardView.Analysis(
                    new DashboardView.AnalysisIdentity(analysisId, 1),
                    "CANDIDATES_READY",
                    List.of(candidate),
                    null
                ),
                new DashboardView.AnalysisSource("OBSERVABILITY", "main", "main", "abcdef"),
                Instant.parse("2026-08-25T00:00:00Z")
            ),
            List.of(new DashboardView.CandidateWorkflow(candidate, null))
        );
    }
}
