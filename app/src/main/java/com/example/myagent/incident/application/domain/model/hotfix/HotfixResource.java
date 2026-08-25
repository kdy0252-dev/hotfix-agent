package com.example.myagent.incident.application.domain.model.hotfix;

import java.util.List;

public record HotfixResource(Identity identity, Progress progress, Publication publication) {
    public record Identity(String hotfixId, String analysisId, String candidateId) {
    }

    public record Progress(
        WorkflowState workflow,
        ChangeMetrics changes,
        Verification verification
    ) {
        public Status status() {
            return workflow.status();
        }

        public String branchName() {
            return workflow.branchName();
        }

        public ExecutionDetail activity() {
            return workflow.activity();
        }

        public FailureDetail failure() {
            return workflow.failure();
        }

        public int changedFiles() {
            return changes.changedFiles();
        }

        public int changedLines() {
            return changes.changedLines();
        }

        public String humanReviewReason() {
            return failure() == null ? null : failure().message();
        }
    }

    public record WorkflowState(
        Status status,
        String branchName,
        ExecutionDetail activity,
        FailureDetail failure
    ) {
    }

    public record ExecutionDetail(WorkflowStage stage, String message) {
    }

    public record ChangeMetrics(int changedFiles, int changedLines) {
        public static ChangeMetrics empty() {
            return new ChangeMetrics(0, 0);
        }
    }

    public record FailureDetail(WorkflowStage stage, String code, String message) {
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

    public record StageResult(String name, int exitCode, boolean required, String summary) {
    }

    public record Publication(
        String reviewBranchUrl,
        String draftPullRequestUrl,
        String ciBuildUrl,
        CiPipeline ciPipeline
    ) {
        public Publication(
            String reviewBranchUrl,
            String draftPullRequestUrl,
            String ciBuildUrl,
            String ciResult
        ) {
            this(reviewBranchUrl, draftPullRequestUrl, ciBuildUrl, CiPipeline.empty(ciResult));
        }

        public String ciResult() {
            return ciPipeline == null ? null : ciPipeline.status();
        }

        public List<CiStage> ciStages() {
            return ciPipeline == null ? List.of() : ciPipeline.stages();
        }

        public static Publication empty() {
            return new Publication(null, null, null, CiPipeline.empty(null));
        }

        public static Publication forHumanReview(String reviewBranchUrl) {
            return new Publication(reviewBranchUrl, null, null, CiPipeline.empty(null));
        }
    }

    public record CiPipeline(String status, List<CiStage> stages) {
        public CiPipeline {
            stages = stages == null ? List.of() : List.copyOf(stages);
        }

        public static CiPipeline empty(String status) {
            return new CiPipeline(status, List.of());
        }
    }

    public record CiStage(
        String id,
        String name,
        String status,
        CiTiming timing,
        String detail
    ) {
        public long startTimeMillis() {
            return timing.startTimeMillis();
        }

        public long durationMillis() {
            return timing.durationMillis();
        }
    }

    public record CiTiming(long startTimeMillis, long durationMillis) {
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

    public enum WorkflowStage {
        WORKSPACE_PREPARATION,
        PATCH_GENERATION,
        FOCUSED_VERIFICATION,
        CODE_REVIEW,
        PARITY_VERIFICATION,
        DRAFT_PR_PUBLICATION,
        CI
    }
}
