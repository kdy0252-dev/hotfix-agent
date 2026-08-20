package com.example.myagent.incident.application.domain.model.analysis;

public record SourceSpec(Type type, String branchName, Long pullRequestId) {
    public enum Type {
        BRANCH,
        PULL_REQUEST
    }

    public static SourceSpec branch(String branchName) {
        return new SourceSpec(Type.BRANCH, branchName, null);
    }

    public static SourceSpec pullRequest(long pullRequestId) {
        return new SourceSpec(Type.PULL_REQUEST, null, pullRequestId);
    }
}
