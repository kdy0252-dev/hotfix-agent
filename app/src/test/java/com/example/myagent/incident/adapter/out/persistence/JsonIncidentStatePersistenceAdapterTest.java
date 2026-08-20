package com.example.myagent.incident.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentStatePort.AnalysisEnvelope;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class JsonIncidentStatePersistenceAdapterTest {

    @Test
    void restoresAnalysisAndHotfixStateWithIdempotencyIndexesAfterRestart(
        @TempDir Path temporaryDirectory
    ) {
        var objectMapper = new ObjectMapper();
        var writer = new JsonIncidentStatePersistenceAdapter(
            objectMapper,
            temporaryDirectory.toString()
        );
        var analysisEnvelope = analysisEnvelope();
        var hotfixEnvelope = hotfixEnvelope(analysisEnvelope.session());

        writer.saveAnalysis(analysisEnvelope).get();
        writer.saveHotfix(hotfixEnvelope).get();
        var restarted = new JsonIncidentStatePersistenceAdapter(
            objectMapper,
            temporaryDirectory.toString()
        );

        assertThat(restarted.findAnalysis(analysisEnvelope.session().identity().analysisId()).get())
            .contains(analysisEnvelope);
        assertThat(restarted.findAnalysisByIdempotencyKey("analysis-key").get())
            .contains(analysisEnvelope);
        assertThat(restarted.findHotfix(hotfixEnvelope.resource().identity().hotfixId()).get())
            .contains(hotfixEnvelope);
        assertThat(restarted.findHotfixByIdempotencyKey("hotfix-key").get())
            .contains(hotfixEnvelope);
    }

    private AnalysisEnvelope analysisEnvelope() {
        String analysisId = UUID.randomUUID().toString();
        var session = new AnalysisSession(
            new AnalysisSession.Identity(analysisId, 1, "analysis-hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                null,
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.ANALYSIS_REQUESTED,
                List.of(),
                null
            )
        );
        return new AnalysisEnvelope(1, "analysis-key", "analysis-hash", session);
    }

    private HotfixEnvelope hotfixEnvelope(AnalysisSession analysis) {
        String hotfixId = UUID.randomUUID().toString();
        var resource = new HotfixResource(
            new HotfixResource.Identity(
                hotfixId,
                analysis.identity().analysisId(),
                "candidate-1"
            ),
            new HotfixResource.Progress(
                HotfixResource.Status.SELECTED,
                null,
                0,
                0,
                HotfixResource.Verification.empty(),
                null
            ),
            new HotfixResource.Publication(null, null, null)
        );
        return new HotfixEnvelope(1, "hotfix-key", "hotfix-hash", resource);
    }
}
