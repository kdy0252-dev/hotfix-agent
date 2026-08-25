package com.example.myagent.incident.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.incident.adapter.out.persistence.entity.IncidentAnalysisEntity;
import com.example.myagent.incident.adapter.out.persistence.entity.IncidentHotfixEntity;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentStatePort.AnalysisEnvelope;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentStateEntityMappingTest {

    @Test
    void restoresAnalysisCandidatesAndEvidenceFromRelationalRows() {
        var envelope = analysisEnvelope();

        assertThat(IncidentAnalysisEntity.from(envelope).toDomain()).isEqualTo(envelope);
    }

    @Test
    void restoresHotfixVerificationStagesFromRelationalRows() {
        var envelope = hotfixEnvelope();

        assertThat(IncidentHotfixEntity.from(envelope).toDomain()).isEqualTo(envelope);
    }

    private AnalysisEnvelope analysisEnvelope() {
        var candidate = new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "compile failure",
                "missing symbol",
                0.93,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of("eu/src/Foo.java:12"),
                List.of("jenkins:181"),
                List.of("no runtime regression")
            ),
            new BugCandidate.Recommendation("restore import", "run focused test")
        );
        var session = new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "analysis-hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                new SourceRevision("commit-1", "main", "BRANCH"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate),
                null
            )
        );
        return new AnalysisEnvelope(
            1,
            "analysis-key",
            "analysis-hash",
            session,
            new AnalysisRequest.Jenkins("FMS-EU/job/main", 1, SourceSpec.branch("main"))
        );
    }

    private HotfixEnvelope hotfixEnvelope() {
        var verification = HotfixResource.Verification.focused(
            1,
            "base-commit",
            "patch-commit",
            List.of(new HotfixResource.StageResult("gradle", 0, true, "passed"))
        );
        var resource = new HotfixResource(
            new HotfixResource.Identity("hotfix-1", "analysis-1", "candidate-1"),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.VERIFYING,
                    "agent/hotfix/demo",
                    new HotfixResource.ExecutionDetail(
                        HotfixResource.WorkflowStage.FOCUSED_VERIFICATION,
                        "집중 테스트 중"
                    ),
                    null
                ),
                new HotfixResource.ChangeMetrics(1, 12),
                verification
            ),
            new HotfixResource.Publication(
                null,
                "https://bitbucket.example/pull-requests/99",
                "https://jenkins.example/job/PR-99/1/",
                new HotfixResource.CiPipeline("IN_PROGRESS", List.of(
                    new HotfixResource.CiStage(
                        "31",
                        "Test",
                        "IN_PROGRESS",
                        new HotfixResource.CiTiming(1_787_527_557_221L, 47_193L),
                        null
                    )
                ))
            )
        );
        return new HotfixEnvelope(
            1,
            "hotfix-key",
            "hotfix-hash",
            resource,
            Instant.parse("2026-08-24T01:00:00Z")
        );
    }
}
