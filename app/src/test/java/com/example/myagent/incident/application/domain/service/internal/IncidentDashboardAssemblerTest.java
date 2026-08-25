package com.example.myagent.incident.application.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.dashboard.IncidentDashboardView;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentDashboardAssemblerTest {
    private final IncidentDashboardAssembler assembler = new IncidentDashboardAssembler();

    @Test
    void enablesDraftSelectionWhenPrecisionAnalysisGroundsAnEligibleCandidate() {
        var candidate = candidate(
            BugCandidate.Eligibility.ELIGIBLE,
            List.of("eu/eu-app/src/main/java/BookingService.java:84"),
            List.of("loki:trace-1")
        );

        var view = assembler.analysis(analysis(candidate));

        assertThat(view.candidates().getFirst().eligibility()).isEqualTo("ELIGIBLE");
    }

    @Test
    void keepsDraftSelectionDisabledWhenPrecisionAnalysisHasNoSourceLocation() {
        var candidate = candidate(
            BugCandidate.Eligibility.ELIGIBLE,
            List.of(),
            List.of("loki:trace-1")
        );

        var view = assembler.analysis(analysis(candidate));

        assertThat(view.candidates().getFirst().eligibility())
            .isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void presentsLocalVerificationAsAnOrderedPipeline() {
        var resource = resource(
            HotfixResource.Status.VERIFYING,
            HotfixResource.WorkflowStage.CODE_REVIEW,
            HotfixResource.Publication.empty()
        );

        var stages = pipelineStages(resource);

        assertThat(stages)
            .extracting(stage -> stage.name(), stage -> stage.status())
            .containsExactly(
                tuple("집중 빌드·테스트", "SUCCESS"),
                tuple("AI 코드 검토", "IN_PROGRESS"),
                tuple("Jenkins 동등성 검증", "NOT_EXECUTED")
            );
    }

    @Test
    void presentsThePipelineStagesCollectedFromJenkins() {
        var publication = new HotfixResource.Publication(
            null,
            "https://bitbucket.example/pull-requests/99",
            "https://jenkins.example/job/PR-99/1/",
            new HotfixResource.CiPipeline("IN_PROGRESS", List.of(
                new HotfixResource.CiStage(
                    "10",
                    "Checkout SCM",
                    "SUCCESS",
                    new HotfixResource.CiTiming(100L, 2_000L),
                    null
                ),
                new HotfixResource.CiStage(
                    "31",
                    "Test",
                    "IN_PROGRESS",
                    new HotfixResource.CiTiming(2_100L, 3_000L),
                    null
                )
            ))
        );
        var resource = resource(
            HotfixResource.Status.DRAFT_PR_CREATED,
            HotfixResource.WorkflowStage.CI,
            publication
        );

        var stages = pipelineStages(resource);

        assertThat(stages)
            .extracting(stage -> stage.name(), stage -> stage.status())
            .containsExactly(
                tuple("Checkout SCM", "SUCCESS"),
                tuple("Test", "IN_PROGRESS")
            );
    }

    private List<IncidentDashboardView.PipelineStage> pipelineStages(HotfixResource resource) {
        var envelope = new IncidentStatePort.HotfixEnvelope(
            1,
            "key",
            "hash",
            resource,
            Instant.parse("2026-08-24T01:00:00Z")
        );
        return assembler.hotfixProgresses(List.of(envelope)).getFirst()
            .progress().stageState().pipelineStages();
    }

    private AnalysisSession analysis(BugCandidate candidate) {
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 2, "hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                new SourceRevision("commit-1", "main", "bitbucket:branch:main"),
                Instant.parse("2026-08-24T01:00:00Z"),
                Instant.parse("2026-08-25T01:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate),
                null
            )
        );
    }

    private BugCandidate candidate(
        BugCandidate.Eligibility eligibility,
        List<String> sourceLocations,
        List<String> evidenceRefs
    ) {
        return new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Unique constraint violation",
                "Manual dispatch inserts the same booking twice",
                0.9,
                eligibility
            ),
            new BugCandidate.Evidence(sourceLocations, evidenceRefs, List.of()),
            new BugCandidate.Recommendation("Make dispatch idempotent", "Run booking tests")
        );
    }

    private HotfixResource resource(
        HotfixResource.Status status,
        HotfixResource.WorkflowStage stage,
        HotfixResource.Publication publication
    ) {
        return new HotfixResource(
            new HotfixResource.Identity("hotfix-1", "analysis-1", "candidate-1"),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    status,
                    "agent/hotfix/example",
                    new HotfixResource.ExecutionDetail(stage, "진행 중"),
                    null
                ),
                HotfixResource.ChangeMetrics.empty(),
                HotfixResource.Verification.empty()
            ),
            publication
        );
    }
}
