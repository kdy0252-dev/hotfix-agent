package com.example.myagent.dashboard.application.domain.model.view;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DashboardView {
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KOREA_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'");
    private static final DateTimeFormatter INPUT_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private DashboardView() {
    }

    public record FailedPullRequest(
        PullRequestReference pullRequest,
        BranchReference branch,
        BuildReference build
    ) {
    }

    public record PullRequestReference(long number, String url) {
    }

    public record BranchReference(String name, String commit) {
    }

    public record BuildReference(
        String jobPath,
        long number,
        String result,
        Instant timestamp,
        String url
    ) {
    }

    public record ObservabilitySignal(
        String type,
        String title,
        String summary,
        Instant occurredAt,
        SignalReference reference
    ) {
        public String occurredAtKstLabel() {
            return KOREA_DATE_TIME.format(occurredAt.atZone(KOREA_ZONE));
        }

        public String analysisStartAt() {
            return INPUT_DATE_TIME.format(occurredAt.minus(Duration.ofMinutes(5))
                .atZone(KOREA_ZONE));
        }

        public String analysisEndAt() {
            return INPUT_DATE_TIME.format(occurredAt.plus(Duration.ofMinutes(5))
                .atZone(KOREA_ZONE));
        }

        public String analysisKey() {
            String traceId = reference.traceId();
            return traceId == null || traceId.isBlank()
                ? "log-" + occurredAt.toEpochMilli() : traceId;
        }

        public String severity() {
            String normalizedTitle = Objects.toString(title, "").toUpperCase(Locale.ROOT);
            if (containsErrorLevel(normalizedTitle)) {
                return "ERROR";
            }
            if (containsWarningLevel(normalizedTitle)) {
                return "WARNING";
            }
            String supportingText = String.join(
                " ",
                Objects.toString(summary, ""),
                Objects.toString(reference.technicalDetail(), "")
            ).toUpperCase(Locale.ROOT);
            return containsErrorLevel(supportingText) ? "ERROR" : "WARNING";
        }

        private boolean containsErrorLevel(String value) {
            return value.contains("ERROR") || value.contains("FATAL")
                || value.contains("CRITICAL") || value.contains("에러")
                || value.contains("오류");
        }

        private boolean containsWarningLevel(String value) {
            return value.contains("WARN") || value.contains("경고");
        }
    }

    public record SignalReference(
        String traceId,
        String technicalDetail,
        String linkLabel,
        String url
    ) {
    }

    public record HotfixProgress(Identity identity, Progress progress, Links links) {
    }

    public record Identity(String hotfixId, String analysisId, String candidateId) {
    }

    public record Progress(
        String status,
        String branchName,
        StageState stageState,
        FailureDetail failure,
        List<VerificationDetail> verifications
    ) {
        public Progress {
            verifications = List.copyOf(verifications);
        }

        public int currentStep() {
            return stageState.currentStep();
        }

        public int totalSteps() {
            return stageState.totalSteps();
        }

        public String message() {
            return stageState.message();
        }

        public int percent() {
            return Math.min(100, Math.max(0, currentStep() * 100 / totalSteps()));
        }
    }

    public record StageState(
        int currentStep,
        int totalSteps,
        String stage,
        String message,
        StageExecution execution
    ) {
        public StageState(int currentStep, int totalSteps, String stage, String message) {
            this(currentStep, totalSteps, stage, message, StageExecution.empty());
        }

        public StageState(
            int currentStep,
            int totalSteps,
            String stage,
            String message,
            Instant startedAt
        ) {
            this(currentStep, totalSteps, stage, message, new StageExecution(startedAt, List.of()));
        }

        public String stageLabel() {
            return switch (stage == null ? "UNKNOWN" : stage) {
                case "WORKSPACE_PREPARATION" -> "작업공간 준비";
                case "PATCH_GENERATION" -> "수정 코드 생성";
                case "FOCUSED_VERIFICATION" -> "집중 빌드·테스트";
                case "CODE_REVIEW" -> "AI 코드 검토";
                case "PARITY_VERIFICATION" -> "Jenkins 동등성 검증";
                case "DRAFT_PR_PUBLICATION" -> "Draft PR 게시";
                case "CI" -> "Draft PR Jenkins CI";
                default -> "작업 준비";
            };
        }

        public String currentActor() {
            return switch (stage == null ? "UNKNOWN" : stage) {
                case "WORKSPACE_PREPARATION", "PATCH_GENERATION" -> "patch-author-agent";
                case "FOCUSED_VERIFICATION" -> "local-verification";
                case "CODE_REVIEW" -> "patch-review-agent";
                case "PARITY_VERIFICATION" -> "jenkins-parity-verification";
                case "DRAFT_PR_PUBLICATION" -> "bitbucket-draft-pr-publisher";
                case "CI" -> "Jenkins CI";
                default -> "hotfix-orchestrator";
            };
        }

        public String elapsedLabel() {
            return elapsed(execution.startedAt());
        }

        public List<PipelineStage> pipelineStages() {
            return execution.pipelineStages();
        }
    }

    public record StageExecution(Instant startedAt, List<PipelineStage> pipelineStages) {
        public StageExecution {
            pipelineStages = pipelineStages == null ? List.of() : List.copyOf(pipelineStages);
        }

        public static StageExecution empty() {
            return new StageExecution(null, List.of());
        }
    }

    public record PipelineStage(String name, String status, long durationMillis, String detail) {
        public String durationLabel() {
            if (durationMillis <= 0) {
                return "";
            }
            long seconds = Math.max(1, durationMillis / 1_000);
            return seconds < 60 ? seconds + "초" : seconds / 60 + "분 " + seconds % 60 + "초";
        }
    }

    public record FailureDetail(
        String stage,
        String code,
        String message,
        boolean humanFixAvailable
    ) {
        public String stageLabel() {
            return switch (stage == null ? "UNKNOWN" : stage) {
                case "WORKSPACE_PREPARATION" -> "작업공간 준비";
                case "PATCH_GENERATION" -> "수정 코드 생성";
                case "FOCUSED_VERIFICATION" -> "집중 빌드·테스트";
                case "CODE_REVIEW" -> "AI 코드 검토";
                case "PARITY_VERIFICATION" -> "Jenkins 동등성 검증";
                case "DRAFT_PR_PUBLICATION" -> "Draft PR 게시";
                case "CI" -> "Draft PR Jenkins CI";
                default -> "단계 확인 필요";
            };
        }

        public String recoveryGuide() {
            if (humanFixAvailable) {
                return "검토 branch를 게시하고 사람이 수정 commit을 push한 뒤 다시 검증하세요.";
            }
            return "수정 가능한 branch가 만들어지기 전 실패했습니다. 저장소·기준 commit·worktree 설정을 확인한 뒤 재시작하세요.";
        }
    }

    public record VerificationDetail(
        String name,
        int exitCode,
        boolean required,
        String summary
    ) {
        public boolean passed() {
            return exitCode == 0;
        }
    }

    public record Links(String reviewBranchUrl, String draftPullRequestUrl, String ciBuildUrl) {
    }

    public record Analysis(
        AnalysisIdentity identity,
        String status,
        List<Candidate> candidates,
        String failureReason
    ) {
        public Analysis {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public boolean completed() {
            return "CANDIDATES_READY".equals(status)
                || "NEEDS_HUMAN_REVIEW".equals(status)
                || "FAILED".equals(status);
        }

        public long selectableCandidateCount() {
            return candidates.stream().filter(Candidate::selectable).count();
        }
    }

    public record AnalysisIdentity(String analysisId, long version) {
    }

    public record StoredAnalysis(Analysis analysis, AnalysisSource source, Instant createdAt) {
        public String elapsedLabel() {
            return elapsed(createdAt);
        }
    }

    public record AnalysisSource(String type, String reference, String branch, String commit) {
    }

    public record WorkflowItem(
        StoredAnalysis storedAnalysis,
        List<CandidateWorkflow> candidateWorkflows
    ) {
        public WorkflowItem {
            candidateWorkflows = List.copyOf(candidateWorkflows);
        }

        public boolean active() {
            return !storedAnalysis.analysis().completed()
                || candidateWorkflows.stream()
                    .map(CandidateWorkflow::hotfix)
                    .filter(Objects::nonNull)
                    .map(HotfixProgress::progress)
                    .map(Progress::status)
                    .anyMatch(status -> "SELECTED".equals(status)
                        || "PATCHING".equals(status)
                        || "VERIFYING".equals(status)
                        || "DRAFT_PR_CREATED".equals(status));
        }
    }

    public record CandidateWorkflow(Candidate candidate, HotfixProgress hotfix) {
    }

    public record Candidate(
        String candidateId,
        String title,
        String rootCause,
        double confidence,
        String eligibility
    ) {
        public boolean selectable() {
            return "ELIGIBLE".equals(eligibility);
        }

        public String selectionRestriction() {
            return switch (eligibility) {
                case "HUMAN_ONLY" -> "보호 영역 또는 운영 판단이 포함되어 사람 검토가 필요합니다.";
                case "INSUFFICIENT_EVIDENCE" ->
                    "자동 수정에 필요한 코드 위치 또는 증거가 충분하지 않습니다.";
                default -> "자동 수정 자격을 충족하지 못했습니다.";
            };
        }

        public int confidencePercent() {
            return (int) Math.round(confidence * 100);
        }
    }

    public record InterpretationPreview(Metadata metadata, Decision decision) {
    }

    public record Metadata(String interpretationId, long version, Instant expiresAt) {
    }

    public record Decision(
        String status,
        String intent,
        String parameterSummary,
        List<String> clarificationQuestions,
        String rejectionMessage,
        String commandHash
    ) {
        public Decision {
            clarificationQuestions = clarificationQuestions == null
                ? List.of() : List.copyOf(clarificationQuestions);
        }

        public boolean confirmable() {
            return "READY_FOR_CONFIRMATION".equals(status) && commandHash != null;
        }
    }

    public record ExecutionResult(
        String resourceId,
        String status,
        String statusUrl,
        List<String> itemIds
    ) {
        public ExecutionResult {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }

        public boolean analysisResource() {
            return statusUrl != null && statusUrl.startsWith("/api/v1/analyses/");
        }

        public boolean hotfixResource() {
            return statusUrl != null && statusUrl.startsWith("/api/v1/hotfixes/");
        }
    }

    public record ResourceResult(String resourceId, String status, String statusUrl) {
    }

    private static String elapsed(Instant startedAt) {
        if (startedAt == null) {
            return "경과 시간 확인 중";
        }
        long minutes = Math.max(0, Duration.between(startedAt, Instant.now()).toMinutes());
        return minutes == 0 ? "1분 미만" : minutes + "분째";
    }
}
