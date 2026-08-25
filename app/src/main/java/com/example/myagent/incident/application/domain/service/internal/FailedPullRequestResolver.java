package com.example.myagent.incident.application.domain.service.internal;

import com.example.myagent.global.annotation.InternalService;
import com.example.myagent.incident.application.domain.model.dashboard.IncidentDashboardView;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.JenkinsDashboardPort;
import com.example.myagent.incident.application.port.out.PullRequestDashboardPort;
import java.util.List;

@InternalService
public class FailedPullRequestResolver {
    private final PullRequestDashboardPort pullRequestDashboardPort;

    public FailedPullRequestResolver(PullRequestDashboardPort pullRequestDashboardPort) {
        this.pullRequestDashboardPort = pullRequestDashboardPort;
    }

    public List<IncidentDashboardView.FailedPullRequest> resolve(
        List<JenkinsDashboardPort.FailedBuild> builds
    ) {
        return builds.stream().map(this::resolve).toList();
    }

    private IncidentDashboardView.FailedPullRequest resolve(
        JenkinsDashboardPort.FailedBuild build
    ) {
        var pullRequest = pullRequestDashboardPort
            .getOpenPullRequest(build.pullRequestNumber())
            .getOrElseThrow(this::failure);
        return new IncidentDashboardView.FailedPullRequest(
            new IncidentDashboardView.PullRequestReference(
                pullRequest.number(),
                pullRequest.pullRequestUrl()
            ),
            new IncidentDashboardView.BranchReference(
                pullRequest.sourceBranch(),
                pullRequest.sourceCommit()
            ),
            new IncidentDashboardView.BuildReference(
                build.jobPath(),
                build.buildNumber(),
                build.result(),
                build.timestamp(),
                build.buildUrl()
            )
        );
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
