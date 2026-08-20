package com.example.myagent.command.application.domain.model.interpretation;

import java.util.List;

public record CommandInterpretationDraft(
    String intent,
    DraftParameters parameters,
    List<String> missingFields,
    List<String> ambiguousFields,
    String rejectionReason
) {
    public record DraftParameters(
        JenkinsParameters jenkins,
        ObservabilityParameters observability,
        CandidateParameters candidate,
        HotfixParameters hotfix
    ) {
    }

    public record JenkinsParameters(
        String jobPath,
        Long buildNumber,
        SourceParameters source
    ) {
    }

    public record ObservabilityParameters(
        String startAt,
        String endAt,
        String environment,
        SourceParameters source
    ) {
    }

    public record CandidateParameters(
        String analysisId,
        Long analysisVersion,
        String candidateId
    ) {
    }

    public record HotfixParameters(String hotfixId) {
    }

    public record SourceParameters(
        String type,
        String branch,
        Long pullRequestNumber
    ) {
    }
}
