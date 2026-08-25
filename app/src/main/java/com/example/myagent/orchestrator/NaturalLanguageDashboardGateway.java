package com.example.myagent.orchestrator;

import java.time.Instant;
import java.util.List;
import org.springframework.modulith.NamedInterface;

@NamedInterface("natural-language-dashboard-gateway")
public interface NaturalLanguageDashboardGateway {
    InterpretationPreview interpret(InterpretationCommand command);

    ExecutionResult execute(ExecutionCommand command);

    record InterpretationCommand(String text, String idempotencyKey) {
    }

    record ExecutionCommand(
        String interpretationId,
        long version,
        String commandHash,
        String idempotencyKey
    ) {
    }

    record InterpretationPreview(
        Metadata metadata,
        Decision decision
    ) {
    }

    record Metadata(String interpretationId, long version, Instant expiresAt) {
    }

    record Decision(
        String status,
        String intent,
        String parameterSummary,
        List<String> clarificationQuestions,
        String rejectionMessage,
        String commandHash
    ) {
        public Decision {
            clarificationQuestions = clarificationQuestions == null
                ? List.of() : List.copyOf(clarificationQuestions);
        }
    }

    record ExecutionResult(
        String resourceId,
        String status,
        String statusUrl,
        List<String> itemIds
    ) {
        public ExecutionResult {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }
}
