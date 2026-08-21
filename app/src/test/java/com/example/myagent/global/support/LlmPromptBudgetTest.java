package com.example.myagent.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.AiInputBudgetProperties;
import com.example.myagent.global.configuration.AiInputBudgetProperties.Role;
import com.example.myagent.global.configuration.AiInputBudgetProperties.RoleBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmPromptBudgetTest {

    @Test
    void keepsHigherPrioritySectionsWithinTheConfiguredConservativeTokenBudget() {
        var roleBudget = new RoleBudget(20, 7);
        var budget = new LlmPromptBudget(new AiInputBudgetProperties(
            roleBudget, roleBudget, roleBudget, 3
        ));

        var prompt = budget.compose(Role.TRIAGE, "policy", List.of(
            new LlmPromptBudget.Section("Evidence", "E".repeat(30)),
            new LlmPromptBudget.Section("Source", "S".repeat(100))
        ));

        assertThat(prompt.text())
            .hasSize(60)
            .contains("policy", "Evidence", "E".repeat(30), "Source")
            .doesNotContain("S".repeat(20));
        assertThat(prompt.maximumOutputTokens()).isEqualTo(7);
    }
}
