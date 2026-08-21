package com.example.myagent.incident.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.test.unit.FakeOperationContext;
import com.example.myagent.global.configuration.AiInputBudgetProperties;
import com.example.myagent.global.configuration.AiInputBudgetProperties.RoleBudget;
import com.example.myagent.global.support.LlmPromptBudget;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.ChangeSummary;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatchReviewAgentAiMockTest {

    @Test
    void patchReviewerIsIndependentAndHasNoTools() {
        var expected = new PatchReviewResult(
            true,
            "Candidate and bounded patch agree",
            List.of()
        );
        var context = FakeOperationContext.create();
        context.expectResponse(expected);
        var patch = new AppliedPatch(
            workspace(),
            new ChangeSummary(List.of("eu/eu-app/src/main/java/BookingService.java"), 4),
            "patch123"
        );

        var result = new PatchReviewAgent(promptBudget()).review(
            new PatchReviewInput(AgentTestFixtures.eligibleCandidate(), patch),
            context
        );

        assertThat(result).isEqualTo(expected);
        var invocation = context.getLlmInvocations().getFirst();
        assertThat(invocation.getPrompt())
            .contains("Independently review", "Do not call tools")
            .contains("patch123");
        assertThat(invocation.getInteraction().getToolGroups()).isEmpty();
        assertThat(invocation.getInteraction().getLlm().getMaxTokens()).isEqualTo(4_000);
    }

    private Workspace workspace() {
        return new Workspace(
            "/tmp/agent-worktree",
            "agent/hotfix/12345678-null-booking-response",
            "base123",
            Map.of(
                "eu/eu-app/src/main/java/BookingService.java",
                "final class BookingService { void run() {} }"
            )
        );
    }

    private LlmPromptBudget promptBudget() {
        var roleBudget = new RoleBudget(30_000, 4_000);
        return new LlmPromptBudget(new AiInputBudgetProperties(
            roleBudget, roleBudget, roleBudget, 3
        ));
    }
}
