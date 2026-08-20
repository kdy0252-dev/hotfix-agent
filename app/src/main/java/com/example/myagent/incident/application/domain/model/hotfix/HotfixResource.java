package com.example.myagent.incident.application.domain.model.hotfix;

import java.util.List;

public record HotfixResource(Identity identity, Progress progress, Publication publication) {
    public record Identity(String hotfixId, String analysisId, String candidateId) {
    }

    public record Progress(
        Status status,
        String branchName,
        int changedFiles,
        int changedLines,
        Verification verification,
        String humanReviewReason
    ) {
    }

    public record Verification(
        int focusedAttempts,
        VerificationProvenance provenance,
        List<StageResult> stages
    ) {
        public Verification {
            stages = stages == null ? List.of() : List.copyOf(stages);
        }

        public static Verification empty() {
            return new Verification(0, null, List.of());
        }

        public static Verification focused(
            int attempt,
            String baseCommit,
            String patchCommit,
            List<StageResult> stages
        ) {
            return new Verification(
                attempt,
                new VerificationProvenance(baseCommit, patchCommit, null),
                stages
            );
        }
    }

    public record VerificationProvenance(
        String baseCommit,
        String patchCommit,
        JenkinsfileProfile jenkinsfile
    ) {
    }

    public record JenkinsfileProfile(String path, String sha256, int profileVersion) {
    }

    public record StageResult(String name, int exitCode, boolean required) {
    }

    public record Publication(String draftPullRequestUrl, String ciBuildUrl, String ciResult) {
    }

    public enum Status {
        SELECTED,
        PATCHING,
        VERIFYING,
        NEEDS_HUMAN_REVIEW,
        DRAFT_PR_CREATED,
        RESOLVED,
        FAILED
    }
}
