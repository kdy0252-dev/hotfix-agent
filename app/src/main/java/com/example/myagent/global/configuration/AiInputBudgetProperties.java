package com.example.myagent.global.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.ai.input-budget")
public record AiInputBudgetProperties(
    RoleBudget triage,
    RoleBudget reasoning,
    RoleBudget review,
    int charactersPerToken
) {
    public AiInputBudgetProperties {
        if (triage == null || reasoning == null || review == null || charactersPerToken < 1) {
            throw new IllegalArgumentException("AI input budget values must be positive");
        }
    }

    public RoleBudget forRole(Role role) {
        return switch (role) {
            case TRIAGE -> triage;
            case REASONING -> reasoning;
            case REVIEW -> review;
        };
    }

    public record RoleBudget(int maxInputTokens, int maxOutputTokens) {
        public RoleBudget {
            if (maxInputTokens < 1 || maxOutputTokens < 1) {
                throw new IllegalArgumentException("AI role budget values must be positive");
            }
        }
    }

    public enum Role {
        TRIAGE,
        REASONING,
        REVIEW
    }
}
