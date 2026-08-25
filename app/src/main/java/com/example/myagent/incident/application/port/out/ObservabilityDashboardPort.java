package com.example.myagent.incident.application.port.out;

import io.vavr.control.Either;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public interface ObservabilityDashboardPort {
    Either<IncidentFailure, List<Signal>> findSignals(
        SignalQuery query
    );

    record SignalQuery(OffsetDateTime startAt, OffsetDateTime endAt, String environment) {
    }

    record Signal(
        Type type,
        String title,
        String summary,
        Instant occurredAt,
        Reference reference
    ) {
    }

    record Reference(String traceId, String technicalDetail, String linkLabel, String url) {
    }

    enum Type {
        ALERT,
        STACK_TRACE
    }
}
