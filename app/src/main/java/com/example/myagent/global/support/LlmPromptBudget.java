package com.example.myagent.global.support;

import com.example.myagent.global.configuration.AiInputBudgetProperties;
import com.example.myagent.global.configuration.AiInputBudgetProperties.Role;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LlmPromptBudget {
    private final AiInputBudgetProperties properties;

    public LlmPromptBudget(AiInputBudgetProperties properties) {
        this.properties = properties;
    }

    public Prompt compose(
        Role role,
        String instructions,
        List<Section> prioritizedSections
    ) {
        var roleBudget = properties.forRole(role);
        int maximumCharacters = Math.multiplyExact(
            roleBudget.maxInputTokens(),
            properties.charactersPerToken()
        );
        var prompt = new StringBuilder(limit(instructions, maximumCharacters));
        prioritizedSections.forEach(section -> append(prompt, section, maximumCharacters));
        return new Prompt(prompt.toString(), roleBudget.maxOutputTokens());
    }

    private void append(StringBuilder prompt, Section section, int maximumCharacters) {
        int remaining = maximumCharacters - prompt.length();
        if (remaining < 1) {
            return;
        }
        String value = "\n" + section.label() + ": " + section.value();
        prompt.append(limit(value, remaining));
    }

    private String limit(String value, int maximumLength) {
        String safeValue = value == null ? "" : value;
        return safeValue.substring(0, Math.min(safeValue.length(), maximumLength));
    }

    public record Section(String label, String value) {
    }

    public record Prompt(String text, int maximumOutputTokens) {
    }
}
