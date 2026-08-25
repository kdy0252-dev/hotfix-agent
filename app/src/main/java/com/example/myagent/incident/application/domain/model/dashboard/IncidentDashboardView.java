package com.example.myagent.incident.application.domain.model.dashboard;

import java.time.Instant;
import java.util.List;

public final class IncidentDashboardView {
    private IncidentDashboardView() {
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
        SignalType type,
        String title,
        String summary,
        Instant occurredAt,
        SignalReference reference
    ) {
    }

    public record SignalReference(
        String traceId,
        String technicalDetail,
        String linkLabel,
        String url
    ) {
    }

    public enum SignalType {
        ALERT,
        STACK_TRACE
    }

    public record HotfixProgress(
        Identity identity,
        Progress progress,
        Links links
    ) {
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
    }

    public record StageState(
        int currentStep,
        int totalSteps,
        String stage,
        String message,
        StageExecution execution
    ) {
        public Instant startedAt() {
            return execution.startedAt();
        }

        public List<PipelineStage> pipelineStages() {
            return execution.pipelineStages();
        }
    }

    public record StageExecution(Instant startedAt, List<PipelineStage> pipelineStages) {
        public StageExecution {
            pipelineStages = pipelineStages == null ? List.of() : List.copyOf(pipelineStages);
        }
    }

    public record PipelineStage(String name, String status, long durationMillis, String detail) {
    }

    public record FailureDetail(
        String stage,
        String code,
        String message,
        boolean humanFixAvailable
    ) {
    }

    public record VerificationDetail(
        String name,
        int exitCode,
        boolean required,
        String summary
    ) {
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
    }

    public record AnalysisIdentity(String analysisId, long version) {
    }

    public record StoredAnalysis(
        Analysis analysis,
        AnalysisSource source,
        Instant createdAt
    ) {
    }

    public record AnalysisSource(String type, String reference, String branch, String commit) {
    }

    public record Candidate(
        String candidateId,
        String title,
        String rootCause,
        double confidence,
        String eligibility,
        Refinement refinement
    ) {
    }

    public record Refinement(String status, String failureReason) {
        public boolean active() {
            return "REQUESTED".equals(status) || "RUNNING".equals(status);
        }
    }
}
