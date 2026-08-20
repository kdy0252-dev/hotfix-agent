package com.example.myagent.incident.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.test.unit.FakeOperationContext;
import com.example.myagent.global.configuration.AiInputBudgetProperties;
import com.example.myagent.global.support.LlmPromptBudget;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.ChangeSummary;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.FileUpdate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HotfixImplementationAgentsAiMockTest {

    @Test
    void patchAuthorReturnsCompleteAllowedFileContentWithoutTools() {
        var update = new FileUpdate(
            "eu/eu-app/src/main/java/BookingService.java",
            "final class BookingService {}",
            "Guard the null response"
        );
        var expected = new HotfixImplementationAgents.PatchProposalResult(
            "Guard null booking response",
            List.of(update)
        );
        var context = FakeOperationContext.create();
        context.expectResponse(expected);

        var result = new HotfixImplementationAgents.PatchAuthorAgent(promptBudget()).propose(
            new HotfixImplementationAgents.PatchAuthorInput(
                eligibleCandidate(),
                workspace(),
                1,
                "none"
            ),
            context
        );

        assertThat(result).isEqualTo(expected);
        var invocation = context.getLlmInvocations().getFirst();
        assertThat(invocation.getPrompt())
            .contains("complete replacement content", "Maximum 10 files and 500 changed lines")
            .contains("BookingService.java");
        assertThat(invocation.getInteraction().getToolGroups()).isEmpty();
    }

    @Test
    void patchReviewerIsIndependentAndHasNoTools() {
        var expected = new HotfixImplementationAgents.PatchReviewResult(
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

        var result = new HotfixImplementationAgents.PatchReviewAgent(promptBudget()).review(
            new HotfixImplementationAgents.PatchReviewInput(eligibleCandidate(), patch),
            context
        );

        assertThat(result).isEqualTo(expected);
        var invocation = context.getLlmInvocations().getFirst();
        assertThat(invocation.getPrompt())
            .contains("Independently review", "Do not call tools")
            .contains("patch123");
        assertThat(invocation.getInteraction().getToolGroups()).isEmpty();
    }

    private BugCandidate eligibleCandidate() {
        return new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Null booking response",
                "BookingService dereferences a null response",
                0.95,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of("eu/eu-app/src/main/java/BookingService.java:84"),
                List.of("jenkins:181:console"),
                List.of()
            ),
            new BugCandidate.Recommendation(
                "Guard the response",
                "Run eu-app tests and Jenkins parity"
            )
        );
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
