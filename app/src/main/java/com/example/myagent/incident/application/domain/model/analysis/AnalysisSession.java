package com.example.myagent.incident.application.domain.model.analysis;

import java.time.Instant;
import java.util.List;

public record AnalysisSession(Identity identity, Snapshot snapshot, Result result) {
    public record Identity(String analysisId, long version, String requestHash) {
    }

    public record Snapshot(
        SourceSpec source,
        SourceRevision sourceRevision,
        Instant createdAt,
        Instant expiresAt
    ) {
    }

    public record Result(Status status, List<BugCandidate> candidates, String failureReason) {
        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public enum Status {
        ANALYSIS_REQUESTED,
        ANALYZING,
        CANDIDATES_READY,
        NEEDS_HUMAN_REVIEW,
        FAILED
    }
}
