package com.example.myagent.orchestrator;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.modulith.NamedInterface;

@NamedInterface("incident-dashboard-gateway")
public interface IncidentDashboardGateway {
    List<FailedPullRequest> failedPullRequests();

    ResourceResult requestJenkinsAnalysis(JenkinsAnalysisCommand command);

    List<ObservabilitySignal> observabilitySignals(ObservabilityQuery query);

    ResourceResult requestObservabilityAnalysis(ObservabilityAnalysisCommand command);

    List<HotfixProgress> hotfixProgresses();

    List<StoredAnalysis> recentAnalyses();

    Analysis analysis(String analysisId);

    Analysis refineCandidate(RefinementCommand command);

    ResourceResult selectCandidate(SelectionCommand command);

    ResourceResult restartHotfix(RestartCommand command);

    ResourceResult publishHumanReviewBranch(String hotfixId);

    ResourceResult verifyHumanChanges(String hotfixId);

    HotfixProgress refreshHotfixCi(String hotfixId);

    void cancelAndDeleteHotfix(String hotfixId);

    void cancelAndDeleteWorkflow(String analysisId);

    record FailedPullRequest(
        PullRequestReference pullRequest,
        BranchReference branch,
        BuildReference build
    ) {
    }

    record PullRequestReference(long number, String url) {
    }

    record BranchReference(String name, String commit) {
    }

    record BuildReference(
        String jobPath,
        long number,
        String result,
        Instant timestamp,
        String url
    ) {
    }

    record ObservabilityQuery(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String environment
    ) {
    }

    record ObservabilityAnalysisCommand(
        ObservabilityQuery observation,
        Source source,
        String idempotencyKey
    ) {
    }

    record Source(String type, String branchName, Long pullRequestId) {
    }

    record JenkinsAnalysisCommand(
        String jobPath,
        long buildNumber,
        long pullRequestId,
        String idempotencyKey
    ) {
    }

    record ObservabilitySignal(
        String type,
        String title,
        String summary,
        Instant occurredAt,
        SignalReference reference
    ) {
    }

    record SignalReference(
        String traceId,
        String technicalDetail,
        String linkLabel,
        String url
    ) {
    }

    record HotfixProgress(Identity identity, Progress progress, Links links) {
    }

    record Identity(String hotfixId, String analysisId, String candidateId) {
    }

    record Progress(
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

    record StageState(
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

    record StageExecution(Instant startedAt, List<PipelineStage> pipelineStages) {
        public StageExecution {
            pipelineStages = pipelineStages == null ? List.of() : List.copyOf(pipelineStages);
        }
    }

    record PipelineStage(String name, String status, long durationMillis, String detail) {
    }

    record FailureDetail(
        String stage,
        String code,
        String message,
        boolean humanFixAvailable
    ) {
    }

    record VerificationDetail(
        String name,
        int exitCode,
        boolean required,
        String summary
    ) {
    }

    record Links(String reviewBranchUrl, String draftPullRequestUrl, String ciBuildUrl) {
    }

    record Analysis(
        AnalysisIdentity identity,
        String status,
        List<Candidate> candidates,
        String failureReason
    ) {
        public Analysis {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record AnalysisIdentity(String analysisId, long version) {
    }

    record StoredAnalysis(Analysis analysis, AnalysisSource source, Instant createdAt) {
    }

    record AnalysisSource(String type, String reference, String branch, String commit) {
    }

    record Candidate(
        String candidateId,
        String title,
        String rootCause,
        double confidence,
        String eligibility,
        Refinement refinement
    ) {
    }

    record Refinement(String status, String failureReason) {
    }

    record SelectionCommand(
        String analysisId,
        long analysisVersion,
        String candidateId,
        String idempotencyKey,
        String patchInstruction
    ) {
        public SelectionCommand(
            String analysisId,
            long analysisVersion,
            String candidateId,
            String idempotencyKey
        ) {
            this(analysisId, analysisVersion, candidateId, idempotencyKey, "");
        }
    }

    record RefinementCommand(String analysisId, long analysisVersion, String candidateId) {
    }

    record RestartCommand(String hotfixId, String idempotencyKey) {
    }

    record ResourceResult(String resourceId, String status, String statusUrl) {
    }
}
