package com.example.myagent.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.AiInputBudgetProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmPromptBudgetTest {

    @Test
    void keepsHigherPrioritySectionsWithinTheConfiguredConservativeTokenBudget() {
        var budget = new LlmPromptBudget(new AiInputBudgetProperties(20, 3));

        String prompt = budget.compose("policy", List.of(
            new LlmPromptBudget.Section("Evidence", "E".repeat(30)),
            new LlmPromptBudget.Section("Source", "S".repeat(100))
        ));

        assertThat(prompt)
            .hasSize(60)
            .contains("policy", "Evidence", "E".repeat(30), "Source")
            .doesNotContain("S".repeat(20));
    }
}
