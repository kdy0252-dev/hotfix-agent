package com.example.myagent.incident.application.port.out;

import io.vavr.control.Either;
import java.time.Instant;
import java.util.List;

public interface JenkinsDashboardPort {
    Either<IncidentFailure, List<FailedBuild>> findFailedPullRequestBuilds();

    record FailedBuild(
        long pullRequestNumber,
        String jobPath,
        long buildNumber,
        String result,
        Instant timestamp,
        String buildUrl
    ) {
    }
}
