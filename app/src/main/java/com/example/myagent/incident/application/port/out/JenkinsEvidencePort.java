package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import io.vavr.control.Either;

public interface JenkinsEvidencePort {
    Either<IncidentFailure, BuildSnapshot> inspect(AnalysisRequest.Jenkins request);

    Either<IncidentFailure, AnalysisEvidence.Jenkins> collect(AnalysisRequest.Jenkins request);

    Either<IncidentFailure, CiBuildSnapshot> refreshPullRequestBuild(String pullRequestUrl);

    record CiBuildSnapshot(String result, String buildUrl) {
    }

    record BuildSnapshot(String revision) {
    }
}
