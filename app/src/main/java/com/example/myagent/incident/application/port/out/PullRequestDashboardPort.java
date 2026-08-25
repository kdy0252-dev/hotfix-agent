package com.example.myagent.incident.application.port.out;

import io.vavr.control.Either;

public interface PullRequestDashboardPort {
    Either<IncidentFailure, PullRequestDetails> getOpenPullRequest(long pullRequestNumber);

    record PullRequestDetails(
        long number,
        String sourceBranch,
        String sourceCommit,
        String pullRequestUrl
    ) {
    }
}
