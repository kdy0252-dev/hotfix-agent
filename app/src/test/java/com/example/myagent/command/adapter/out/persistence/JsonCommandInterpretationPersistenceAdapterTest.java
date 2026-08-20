package com.example.myagent.command.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.command.application.domain.model.command.CommandIntent;
import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.StateEntry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class JsonCommandInterpretationPersistenceAdapterTest {

    @Test
    void writesAndReadsTypedCommandState(@TempDir Path temporaryDirectory) {
        var adapter = new JsonCommandInterpretationPersistenceAdapter(
            new ObjectMapper(),
            temporaryDirectory.toString()
        );
        var interpretation = interpretation();

        var saved = adapter.save(new StateEntry("key-1", "body-hash", interpretation));
        var foundById = adapter.findById(interpretation.metadata().interpretationId());
        var foundByKey = adapter.findByIdempotencyKey("key-1");

        assertThat(saved.isRight()).isTrue();
        assertThat(foundById.get()).contains(interpretation);
        assertThat(foundByKey.get()).isPresent();
        assertThat(foundByKey.get().orElseThrow().requestBodyHash()).isEqualTo("body-hash");
    }

    private CommandInterpretation interpretation() {
        var policy = CommandInterpretation.PolicyPreview.fixedPolicy();
        var command = new InterpretedCommand(
            CommandIntent.ANALYZE_JENKINS,
            new CommandParameters.JenkinsAnalysis(
                "FMS-EU/main",
                181L,
                new SourceReference.PullRequest(1285L)
            )
        );
        return new CommandInterpretation(
            new CommandInterpretation.Metadata(
                "167dc6b2-4a9b-4b1b-93fb-98ecb0f544f1",
                1L,
                new CommandInterpretation.RequestFingerprint("digest", "preview"),
                new CommandInterpretation.Timing(
                    Instant.parse("2026-08-20T01:00:00Z"),
                    Instant.parse("2026-08-20T01:10:00Z")
                )
            ),
            new CommandInterpretation.Decision(
                InterpretationStatus.READY_FOR_CONFIRMATION,
                command,
                new CommandInterpretation.Feedback(List.of(), List.of(), null, null),
                policy,
                "command-hash"
            )
        );
    }
}
