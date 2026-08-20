package com.example.myagent.command.application.domain.model.execution;

import java.time.Instant;
import java.util.List;

public record CommandExecution(Identity identity, Result result, Instant executedAt) {
    public record Identity(
        String executionId,
        String interpretationId,
        String idempotencyKey,
        String requestHash
    ) {
    }

    public record Result(
        String resourceId,
        String status,
        String statusUrl,
        List<String> itemIds
    ) {
        public Result {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }
}
