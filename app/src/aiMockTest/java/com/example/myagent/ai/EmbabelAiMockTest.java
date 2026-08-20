package com.example.myagent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.test.unit.FakeOperationContext;
import org.junit.jupiter.api.Test;

class EmbabelAiMockTest {

    @Test
    void recordsThePromptAndReturnsTheExpectedResponseWithoutCallingAnLlm() {
        var context = FakeOperationContext.create();
        context.expectResponse("ELIGIBLE: NullPointerException at BookingService:84");

        var prompt = """
            Diagnose this Jenkins failure candidate.
            Environment: prod-eu-app
            Evidence: NullPointerException at BookingService:84
            Policy: draft PR only; do not modify migrations or deployment manifests.
            """;

        var assessment = context.ai()
            .withDefaultLlm()
            .withId("diagnose-hotfix-candidate")
            .createObject(prompt, String.class);

        assertThat(assessment)
            .isEqualTo("ELIGIBLE: NullPointerException at BookingService:84");
        assertThat(context.getLlmInvocations()).hasSize(1);
        assertThat(context.getLlmInvocations().getFirst().getPrompt()).isEqualTo(prompt);
        assertThat(context.getLlmInvocations().getFirst().getInteraction().getId())
            .isEqualTo("diagnose-hotfix-candidate");
    }
}
