package com.example.myagent.command.application.domain.model.interpretation;

import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import java.time.Instant;
import java.util.List;

public record CommandInterpretation(Metadata metadata, Decision decision) {

    public record Metadata(
        String interpretationId,
        long version,
        RequestFingerprint request,
        Timing timing
    ) {
    }

    public record RequestFingerprint(String digest, String redactedPreview) {
    }

    public record Timing(Instant createdAt, Instant expiresAt) {
    }

    public record Decision(
        InterpretationStatus status,
        InterpretedCommand command,
        Feedback feedback,
        PolicyPreview policy,
        String commandHash
    ) {
    }

    public record Feedback(
        List<String> missingFields,
        List<String> clarificationQuestions,
        String rejectionCode,
        String rejectionMessage
    ) {
        public Feedback {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
            clarificationQuestions = clarificationQuestions == null
                ? List.of() : List.copyOf(clarificationQuestions);
        }
    }

    public record PolicyPreview(
        String repository,
        String service,
        String delivery,
        String policyVersion
    ) {
        public static PolicyPreview fixedPolicy() {
            return new PolicyPreview("autocrypt/fms", "EU_APP", "DRAFT_PR_ONLY", "v1");
        }
    }
}
