package com.example.myagent.incident.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.test.unit.FakeOperationContext;
import com.example.myagent.global.configuration.AiInputBudgetProperties;
import com.example.myagent.global.support.LlmPromptBudget;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.FileUpdate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatchAuthorAgentAiMockTest {

    @Test
    void patchAuthorReturnsCompleteAllowedFileContentWithoutTools() {
        var update = new FileUpdate(
            "eu/eu-app/src/main/java/BookingService.java",
            "final class BookingService {}",
            "Guard the null response"
        );
        var expected = new PatchProposalResult(
            "Guard null booking response",
            List.of(update)
        );
        var context = FakeOperationContext.create();
        context.expectResponse(expected);

        var result = new PatchAuthorAgent(promptBudget()).propose(
            new PatchAuthorInput(eligibleCandidate(), workspace(), 1, "none"),
            context
        );

        assertThat(result).isEqualTo(expected);
        var invocation = context.getLlmInvocations().getFirst();
        assertThat(invocation.getPrompt())
            .contains("complete replacement content", "Maximum 10 files and 500 changed lines")
            .contains("BookingService.java");
        assertThat(invocation.getInteraction().getToolGroups()).isEmpty();
    }

    private BugCandidate eligibleCandidate() {
        return AgentTestFixtures.eligibleCandidate();
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
        return new LlmPromptBudget(new AiInputBudgetProperties(30_000, 3));
    }
}
