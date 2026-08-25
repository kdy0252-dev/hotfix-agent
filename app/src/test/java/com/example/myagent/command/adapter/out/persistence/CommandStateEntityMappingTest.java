package com.example.myagent.command.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.command.adapter.out.persistence.entity.CommandExecutionEntity;
import com.example.myagent.command.adapter.out.persistence.entity.CommandInterpretationEntity;
import com.example.myagent.command.application.domain.model.command.CommandIntent;
import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.StateEntry;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.RequestPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandStateEntityMappingTest {

    @Test
    void restoresTypedInterpretationFromRelationalColumns() {
        var entry = new StateEntry(
            "key-1",
            new RequestPayload("body-hash", "redacted request"),
            interpretation()
        );

        var restored = CommandInterpretationEntity.from(entry).toDomain();

        assertThat(restored).isEqualTo(entry);
    }

    @Test
    void restoresExecutionAndOrderedItemsFromRelationalColumns() {
        var execution = new CommandExecution(
            new CommandExecution.Identity("execution-1", "interpretation-1", "key-2", "hash"),
            new CommandExecution.Result(
                "analysis-1",
                "ACCEPTED",
                "/api/v1/analyses/analysis-1",
                List.of("candidate-1", "candidate-2")
            ),
            Instant.parse("2026-08-20T01:01:00Z")
        );

        assertThat(CommandExecutionEntity.from(execution).toDomain()).isEqualTo(execution);
    }

    private CommandInterpretation interpretation() {
        var command = new InterpretedCommand(
            CommandIntent.ANALYZE_JENKINS,
            new CommandParameters.JenkinsAnalysis(
                "FMS-EU/main",
                181L,
                new SourceReference.PullRequest(1285L)
            )
        );
        var metadata = new CommandInterpretation.Metadata(
            "167dc6b2-4a9b-4b1b-93fb-98ecb0f544f1",
            1L,
            new CommandInterpretation.RequestFingerprint("digest", "preview"),
            new CommandInterpretation.Timing(
                Instant.parse("2026-08-20T01:00:00Z"),
                Instant.parse("2026-08-20T01:10:00Z")
            )
        );
        var decision = new CommandInterpretation.Decision(
            InterpretationStatus.READY_FOR_CONFIRMATION,
            command,
            new CommandInterpretation.Feedback(List.of(), List.of(), null, null),
            CommandInterpretation.PolicyPreview.fixedPolicy(),
            "command-hash"
        );
        return new CommandInterpretation(metadata, decision);
    }
}
