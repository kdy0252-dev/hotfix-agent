package com.example.myagent.dashboard.application.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalLanguageRequestEnricherTest {
    private final NaturalLanguageRequestEnricher enricher = new NaturalLanguageRequestEnricher();

    @Test
    void resolvesTheLatestFailedPullRequestWhenTheNumberIsOmitted() {
        String enriched = enricher.enrich(
            "PR 최근에 실패한거 분석좀 해줄래?",
            List.of(failedPullRequest(1301), failedPullRequest(1299))
        );

        assertThat(enriched).contains(
            "jobPath: FMS-EU/job/PR-1301",
            "buildNumber: 1",
            "source.type: PULL_REQUEST",
            "source.pullRequestNumber: 1301"
        );
    }

    @Test
    void recognizesARecentFailedBuildWithoutAnExplicitPullRequestWord() {
        String request = "최근 빌드 실패한것 해석해줘";

        assertThat(enricher.needsFailedPullRequestContext(request)).isTrue();
        assertThat(enricher.enrich(request, List.of(failedPullRequest(1301))))
            .contains("jobPath: FMS-EU/job/PR-1301", "source.pullRequestNumber: 1301");
    }

    @Test
    void treatsAShortChatAnalysisRequestAsTheLatestFailedBuild() {
        String request = "분석 해줘";

        assertThat(enricher.needsFailedPullRequestContext(request)).isTrue();
        assertThat(enricher.enrich(request, List.of(failedPullRequest(1301))))
            .contains("jobPath: FMS-EU/job/PR-1301", "source.pullRequestNumber: 1301");
    }

    @Test
    void recoversAnAffirmativeAnalysisPhraseAsTheLatestFailedBuild() {
        String request = "ㅇㅇ 분석해줘";

        assertThat(enricher.needsFailedPullRequestContext(request)).isTrue();
        assertThat(enricher.enrich(request, List.of(failedPullRequest(1301))))
            .contains("jobPath: FMS-EU/job/PR-1301", "source.pullRequestNumber: 1301");
    }

    @Test
    void resolvesTheRequestedFailedPullRequestFromConversationalText() {
        String enriched = enricher.enrich(
            "PR 최근에 실패한거 1299인가 이거 원인 분석좀 해줄래?",
            List.of(failedPullRequest(1301), failedPullRequest(1299))
        );

        assertThat(enriched).contains("PR-1299", "source.pullRequestNumber: 1299");
    }

    private DashboardView.FailedPullRequest failedPullRequest(long pullRequestNumber) {
        return new DashboardView.FailedPullRequest(
            new DashboardView.PullRequestReference(pullRequestNumber, "https://bitbucket/pr"),
            new DashboardView.BranchReference("feature/example", "abcdef"),
            new DashboardView.BuildReference(
                "FMS-EU/job/PR-" + pullRequestNumber,
                1,
                "FAILURE",
                Instant.parse("2026-08-24T00:00:00Z"),
                "https://jenkins/build"
            )
        );
    }
}
