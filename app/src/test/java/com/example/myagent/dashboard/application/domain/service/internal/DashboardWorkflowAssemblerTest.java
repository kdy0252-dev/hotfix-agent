package com.example.myagent.dashboard.application.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardWorkflowAssemblerTest {
    private final DashboardWorkflowAssembler assembler = new DashboardWorkflowAssembler();

    @Test
    void joinsEachHotfixToItsAnalysisAndKeepsAnalysesWithoutASelection() {
        var firstCandidate = candidate("candidate-1", "ELIGIBLE");
        var secondCandidate = candidate("candidate-2", "ELIGIBLE");
        var firstAnalysis = storedAnalysis("analysis-1", "PR-1292", firstCandidate);
        var secondAnalysis = storedAnalysis("analysis-2", "PR-1293", secondCandidate);
        var hotfix = hotfix("analysis-1", "candidate-1");

        var workflows = assembler.assemble(
            List.of(firstAnalysis, secondAnalysis),
            List.of(hotfix)
        );

        assertThat(workflows).containsExactly(
            new DashboardView.WorkflowItem(
                firstAnalysis,
                List.of(new DashboardView.CandidateWorkflow(firstCandidate, hotfix))
            ),
            new DashboardView.WorkflowItem(
                secondAnalysis,
                List.of(new DashboardView.CandidateWorkflow(secondCandidate, null))
            )
        );
    }

    @Test
    void explainsWhyAnInsufficientEvidenceCandidateCannotCreateADraftPullRequest() {
        var candidate = candidate("candidate-1", "INSUFFICIENT_EVIDENCE");

        assertThat(candidate.selectable()).isFalse();
        assertThat(candidate.selectionRestriction()).contains("코드 위치", "증거");
    }

    @Test
    void describesTheCurrentActorAndElapsedStageTime() {
        var stage = new DashboardView.StageState(
            3,
            4,
            "CODE_REVIEW",
            "패치 검토 중",
            Instant.now().minus(Duration.ofMinutes(5))
        );

        assertThat(stage.currentActor()).isEqualTo("patch-review-agent");
        assertThat(stage.elapsedLabel()).isEqualTo("5분째");
    }

    @Test
    void keepsRefreshingTheWorkflowWhileJenkinsCiIsRunning() {
        var workflow = new DashboardView.WorkflowItem(
            storedAnalysis("analysis-1", "PR-1292", candidate("candidate-1", "ELIGIBLE")),
            List.of(new DashboardView.CandidateWorkflow(
                candidate("candidate-1", "ELIGIBLE"),
                hotfix("analysis-1", "candidate-1", "DRAFT_PR_CREATED")
            ))
        );

        assertThat(workflow.active()).isTrue();
    }

    @Test
    void removesFailedAnalysesThatHaveNoActionableCandidateFromTheDashboard() {
        var failed = new DashboardView.StoredAnalysis(
            new DashboardView.Analysis(
                new DashboardView.AnalysisIdentity("analysis-failed", 1),
                "FAILED",
                List.of(),
                "No candidate"
            ),
            new DashboardView.AnalysisSource(
                "PULL_REQUEST", "PR-1301", "feature/test", "abcdef"
            ),
            Instant.parse("2026-08-24T00:00:00Z")
        );

        assertThat(assembler.assemble(List.of(failed), List.of())).isEmpty();
    }

    private DashboardView.StoredAnalysis storedAnalysis(
        String analysisId,
        String reference,
        DashboardView.Candidate candidate
    ) {
        return new DashboardView.StoredAnalysis(
            new DashboardView.Analysis(
                new DashboardView.AnalysisIdentity(analysisId, 1),
                "CANDIDATES_READY",
                List.of(candidate),
                null
            ),
            new DashboardView.AnalysisSource(
                "PULL_REQUEST", reference, "feature/test", "abcdef0123456789"
            ),
            Instant.parse("2026-08-24T00:00:00Z")
        );
    }

    private DashboardView.Candidate candidate(String candidateId, String eligibility) {
        return new DashboardView.Candidate(
            candidateId,
            "Possible classpath issue",
            "The source location is not confirmed.",
            0.3,
            eligibility
        );
    }

    private DashboardView.HotfixProgress hotfix(String analysisId, String candidateId) {
        return hotfix(analysisId, candidateId, "VERIFYING");
    }

    private DashboardView.HotfixProgress hotfix(String analysisId, String candidateId, String status) {
        return new DashboardView.HotfixProgress(
            new DashboardView.Identity("hotfix-1", analysisId, candidateId),
            new DashboardView.Progress(
                status,
                "agent/hotfix/pr-1292",
                new DashboardView.StageState(3, 4, "FOCUSED_VERIFICATION", "검증 중"),
                null,
                List.of()
            ),
            new DashboardView.Links(null, null, null)
        );
    }
}
