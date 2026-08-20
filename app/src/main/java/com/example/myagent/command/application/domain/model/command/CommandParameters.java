package com.example.myagent.command.application.domain.model.command;

import java.time.Instant;

public sealed interface CommandParameters permits CommandParameters.JenkinsAnalysis,
    CommandParameters.ObservabilityAnalysis, CommandParameters.CandidateList,
    CommandParameters.CandidateSelection, CommandParameters.HotfixStatus,
    CommandParameters.CiStatusRefresh {

    record JenkinsAnalysis(
        String jobPath,
        long buildNumber,
        SourceReference source
    ) implements CommandParameters {
    }

    record ObservabilityAnalysis(
        Instant startAt,
        Instant endAt,
        String environment,
        SourceReference source
    ) implements CommandParameters {
    }

    record CandidateList(String analysisId) implements CommandParameters {
    }

    record CandidateSelection(
        String analysisId,
        long analysisVersion,
        String candidateId
    ) implements CommandParameters {
    }

    record HotfixStatus(String hotfixId) implements CommandParameters {
    }

    record CiStatusRefresh(String hotfixId) implements CommandParameters {
    }
}
