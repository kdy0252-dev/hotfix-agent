package com.example.myagent.incident.application.domain.model.analysis;

import java.util.List;

public record BugCandidate(Identity identity, Evidence evidence, Recommendation recommendation) {
    public boolean automaticFixReady() {
        return identity.eligibility() == Eligibility.ELIGIBLE
            && !evidence.sourceLocations().isEmpty()
            && !evidence.evidenceRefs().isEmpty();
    }

    public record Identity(
        String candidateId,
        String title,
        String rootCause,
        double confidence,
        Eligibility eligibility
    ) {
    }

    public record Evidence(
        List<String> sourceLocations,
        List<String> evidenceRefs,
        List<String> counterEvidence
    ) {
        public Evidence {
            sourceLocations = List.copyOf(sourceLocations);
            evidenceRefs = List.copyOf(evidenceRefs);
            counterEvidence = List.copyOf(counterEvidence);
        }
    }

    public record Recommendation(String fixSummary, String verificationSummary) {
    }

    public enum Eligibility {
        ELIGIBLE,
        HUMAN_ONLY,
        INSUFFICIENT_EVIDENCE
    }
}
