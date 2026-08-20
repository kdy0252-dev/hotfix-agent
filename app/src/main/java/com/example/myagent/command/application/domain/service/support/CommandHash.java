package com.example.myagent.command.application.domain.service.support;

import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;

public final class CommandHash {
    private CommandHash() {
    }

    public static String calculate(InterpretedCommand command, String policyVersion) {
        return TextDigest.sha256("v1|" + command.intent() + '|' + parameters(command.parameters())
            + '|' + policyVersion);
    }

    private static String parameters(CommandParameters parameters) {
        if (parameters instanceof CommandParameters.JenkinsAnalysis jenkins) {
            return jenkins.jobPath() + '|' + jenkins.buildNumber() + '|' + source(jenkins.source());
        }
        if (parameters instanceof CommandParameters.ObservabilityAnalysis observability) {
            return observability.startAt() + "|" + observability.endAt() + "|"
                + observability.environment() + '|' + source(observability.source());
        }
        if (parameters instanceof CommandParameters.CandidateList candidateList) {
            return candidateList.analysisId();
        }
        if (parameters instanceof CommandParameters.CandidateSelection selection) {
            return selection.analysisId() + '|' + selection.analysisVersion() + '|'
                + selection.candidateId();
        }
        if (parameters instanceof CommandParameters.HotfixStatus hotfixStatus) {
            return hotfixStatus.hotfixId();
        }
        return ((CommandParameters.CiStatusRefresh) parameters).hotfixId();
    }

    private static String source(SourceReference source) {
        if (source instanceof SourceReference.Branch branch) {
            return "BRANCH|" + branch.name();
        }
        return "PULL_REQUEST|" + ((SourceReference.PullRequest) source).number();
    }
}
