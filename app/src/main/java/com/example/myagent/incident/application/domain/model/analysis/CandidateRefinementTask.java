package com.example.myagent.incident.application.domain.model.analysis;

import java.time.Instant;

public record CandidateRefinementTask(
    String taskId,
    String analysisId,
    String candidateId,
    Status status,
    String failureReason,
    Instant requestedAt,
    Instant updatedAt
) {
    public enum Status {
        REQUESTED,
        RUNNING,
        COMPLETED,
        FAILED;

        public boolean active() {
            return this == REQUESTED || this == RUNNING;
        }
    }
}
