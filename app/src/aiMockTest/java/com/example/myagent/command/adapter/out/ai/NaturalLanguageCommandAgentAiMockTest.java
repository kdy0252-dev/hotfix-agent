package com.example.myagent.command.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.test.unit.FakeOperationContext;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretationDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalLanguageCommandAgentAiMockTest {

    @Test
    void usesStructuredOutputWithoutExposingAnyTools() {
        var expected = new CommandInterpretationDraft(
            "ANALYZE_JENKINS",
            new CommandInterpretationDraft.DraftParameters(
                new CommandInterpretationDraft.JenkinsParameters(
                    "FMS-EU/main",
                    181L,
                    new CommandInterpretationDraft.SourceParameters("PR", null, 1285L)
                ),
                null,
                null,
                null
            ),
            List.of(),
            List.of(),
            null
        );
        var context = FakeOperationContext.create();
        context.expectResponse(expected);
        var agent = new NaturalLanguageCommandAgent();

        var result = agent.interpret(
            new NaturalLanguageCommandAgent.NaturalLanguageInput("PR 1285 빌드 181을 분석해줘"),
            context
        );

        assertThat(result).isEqualTo(expected);
        assertThat(context.getLlmInvocations()).hasSize(1);
        var invocation = context.getLlmInvocations().getFirst();
        assertThat(invocation.getPrompt())
            .contains("<untrusted-user-text>", "PR 1285 빌드 181을 분석해줘")
            .contains("Allowed intents only");
        assertThat(invocation.getInteraction().getToolGroups()).isEmpty();
        assertThat(invocation.getInteraction().getId())
            .isEqualTo("interpret-natural-language-command");
    }
}
