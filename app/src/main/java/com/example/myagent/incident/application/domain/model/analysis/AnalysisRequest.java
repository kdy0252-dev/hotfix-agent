package com.example.myagent.incident.application.domain.model.analysis;

import java.time.OffsetDateTime;

public sealed interface AnalysisRequest permits AnalysisRequest.Jenkins,
    AnalysisRequest.Observability {

    SourceSpec source();

    record Jenkins(
        String jobPath,
        long buildNumber,
        SourceSpec source
    ) implements AnalysisRequest {
    }

    record Observability(
        TimeRange timeRange,
        Environment environment,
        SourceSpec source
    ) implements AnalysisRequest {
    }

    record TimeRange(OffsetDateTime startAt, OffsetDateTime endAt) {
    }

    enum Environment {
        DEV,
        QA,
        PROD
    }
}
