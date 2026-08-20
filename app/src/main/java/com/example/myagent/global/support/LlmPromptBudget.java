package com.example.myagent.global.support;

import com.example.myagent.global.configuration.AiInputBudgetProperties;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LlmPromptBudget {
    private final int maximumCharacters;

    public LlmPromptBudget(AiInputBudgetProperties properties) {
        this.maximumCharacters = Math.multiplyExact(
            properties.maxTokens(),
            properties.charactersPerToken()
        );
    }

    public String compose(String instructions, List<Section> prioritizedSections) {
        var prompt = new StringBuilder(limit(instructions, maximumCharacters));
        prioritizedSections.forEach(section -> append(prompt, section));
        return prompt.toString();
    }

    private void append(StringBuilder prompt, Section section) {
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
}
