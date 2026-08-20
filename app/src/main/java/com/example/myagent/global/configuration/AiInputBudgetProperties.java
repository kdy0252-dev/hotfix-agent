package com.example.myagent.global.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.ai.input-budget")
public record AiInputBudgetProperties(int maxTokens, int charactersPerToken) {
    public AiInputBudgetProperties {
        if (maxTokens < 1 || charactersPerToken < 1) {
            throw new IllegalArgumentException("AI input budget values must be positive");
        }
    }
}
