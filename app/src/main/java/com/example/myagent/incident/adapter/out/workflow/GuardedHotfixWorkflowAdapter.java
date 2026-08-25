package com.example.myagent.incident.adapter.out.workflow;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.global.configuration.ParityProfileProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.ChangeMetrics;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.FailureDetail;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.WorkflowStage;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Publication;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Review;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.PatchProposalPort;
import com.example.myagent.incident.application.port.out.PatchReviewPort;
import com.example.myagent.incident.application.port.out.PatchWorkspacePort;
import com.example.myagent.incident.application.port.out.PullRequestPort;
import com.example.myagent.incident.application.port.out.PullRequestPort.IncidentArtifact;
import com.example.myagent.incident.application.port.out.PullRequestPort.PatchArtifact;
import com.example.myagent.incident.application.port.out.VerificationPort;
import io.vavr.control.Either;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class GuardedHotfixWorkflowAdapter implements HotfixWorkflowPort {
    private final PatchProposalPort patchProposal;
    private final PatchWorkspacePort patchWorkspace;
    private final VerificationPort verification;
    private final PatchReviewPort patchReview;
    private final PullRequestPort pullRequest;
    private final ParityProfileProperties parityProperties;
    private final AgentRuntimeProperties runtimeProperties;

    public GuardedHotfixWorkflowAdapter(
        PatchProposalPort patchProposal,
        PatchWorkspacePort patchWorkspace,
        VerificationPort verification,
        PatchReviewPort patchReview,
        PullRequestPort pullRequest,
        ParityProfileProperties parityProperties,
        AgentRuntimeProperties runtimeProperties
    ) {
        this.patchProposal = patchProposal;
        this.patchWorkspace = patchWorkspace;
        this.verification = verification;
        this.patchReview = patchReview;
        this.pullRequest = pullRequest;
        this.parityProperties = parityProperties;
        this.runtimeProperties = runtimeProperties;
    }

    @Override
    public Either<IncidentFailure, HotfixResource> execute(
        AnalysisSession analysis,
        BugCandidate candidate,
        HotfixResource hotfix,
        Consumer<ProgressUpdate> progressReporter,
        BooleanSupplier cancelled
    ) {
        String hotfixId = hotfix.identity().hotfixId();
        if (cancelled.getAsBoolean()) {
            return Either.left(cancellationFailure());
        }
        if (runtimeProperties.mode() != AgentRuntimeProperties.Mode.DRAFT_PR) {
            return Either.right(withPatchInstruction(
                needsReview(
                    analysis,
                    candidate,
                    hotfixId,
                    null,
                    null,
                    failure(
                        WorkflowStage.PATCH_GENERATION,
                        "REPORT_ONLY_MODE",
                        "AGENT_MODE가 DRAFT_PR이 아니므로 코드 변경을 실행하지 않았습니다."
                    )
                ),
                hotfix.patchInstruction()
            ));
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.PATCHING,
            null,
            WorkflowStage.WORKSPACE_PREPARATION,
            "고정된 기준 commit으로 전용 hotfix worktree를 준비하고 있습니다."
        ));
        var prepared = patchWorkspace.prepare(analysis, candidate, hotfixId);
        if (prepared.isLeft()) {
            return Either.right(withPatchInstruction(needsReview(
                analysis, candidate, hotfixId, null, null,
                failure(WorkflowStage.WORKSPACE_PREPARATION, prepared.getLeft())
            ), hotfix.patchInstruction()));
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.PATCHING,
            prepared.get().branchName(),
            WorkflowStage.PATCH_GENERATION,
            "원인 증거 범위 안에서 최소 수정 코드를 생성하고 있습니다."
        ));
        return executePrepared(
            analysis,
            candidate,
            hotfixId,
            prepared.get(),
            hotfix.patchInstruction(),
            progressReporter,
            cancelled
        ).map(resource -> withPatchInstruction(resource, hotfix.patchInstruction()));
    }

    @Override
    public Either<IncidentFailure, HotfixResource> publishForHumanReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        HotfixResource hotfix
    ) {
        String branchName = hotfix.progress().branchName();
        if (branchName == null) {
            return Either.left(new IncidentFailure(
                "HUMAN_REVIEW_BRANCH_UNAVAILABLE",
                "게시할 hotfix branch가 없습니다. 작업공간 준비 단계부터 다시 실행해야 합니다."
            ));
        }
        var result = patchWorkspace.publishForHumanReview(
            analysis,
            candidate,
            hotfix.identity().hotfixId(),
            branchName
        );
        if (result.isLeft()) {
            return Either.left(result.getLeft());
        }
        var publication = new HotfixResource.Publication(
            result.get().url(),
            hotfix.publication().draftPullRequestUrl(),
            hotfix.publication().ciBuildUrl(),
            hotfix.publication().ciResult()
        );
        return Either.right(new HotfixResource(
            hotfix.identity(), hotfix.patchInstruction(), hotfix.progress(), publication
        ));
    }

    @Override
    public Either<IncidentFailure, HotfixResource> verifyHumanChanges(
        AnalysisSession analysis,
        BugCandidate candidate,
        HotfixResource hotfix,
        Consumer<ProgressUpdate> progressReporter,
        BooleanSupplier cancelled
    ) {
        if (cancelled.getAsBoolean()) {
            return Either.left(cancellationFailure());
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.VERIFYING,
            hotfix.progress().branchName(),
            WorkflowStage.FOCUSED_VERIFICATION,
            "사람이 push한 commit을 다시 불러와 변경 정책과 집중 테스트를 검사하고 있습니다."
        ));
        var reloaded = patchWorkspace.reloadHumanChanges(
            analysis,
            candidate,
            hotfix.identity().hotfixId(),
            hotfix.progress().branchName()
        );
        if (reloaded.isLeft()) {
            return Either.right(humanChangesNeedReview(
                hotfix,
                Verification.empty(),
                failure(WorkflowStage.FOCUSED_VERIFICATION, reloaded.getLeft())
            ));
        }
        AppliedPatch patch = reloaded.get();
        var focused = verification.runFocused(patch, 1);
        if (focused.isLeft() || !passed(focused.get())) {
            String reason = focused.isLeft()
                ? focused.getLeft().message() : "사람 수정 commit의 집중 검증이 실패했습니다.";
            return Either.right(withPatchInstruction(needsReview(
                analysis,
                candidate,
                hotfix.identity().hotfixId(),
                patch.workspace(),
                focused.getOrElse(Verification.empty()),
                failure(WorkflowStage.FOCUSED_VERIFICATION, "HUMAN_PATCH_VERIFICATION_FAILED", reason),
                hotfix.publication()
            ), hotfix.patchInstruction()));
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.VERIFYING,
            patch.workspace().branchName(),
            WorkflowStage.CODE_REVIEW,
            "사람이 수정한 commit을 독립 AI reviewer가 다시 검토하고 있습니다."
        ));
        var review = patchReview.review(candidate, patch);
        if (review.isLeft() || !review.get().approved()) {
            String reason = review.isLeft() ? review.getLeft().message() : review.get().summary();
            return Either.right(withPatchInstruction(needsReview(
                analysis,
                candidate,
                hotfix.identity().hotfixId(),
                patch.workspace(),
                focused.get(),
                failure(WorkflowStage.CODE_REVIEW, "HUMAN_PATCH_REVIEW_REJECTED", reason),
                hotfix.publication()
            ), hotfix.patchInstruction()));
        }
        return verifyAndPublish(
            analysis,
            candidate,
            hotfix.identity().hotfixId(),
            patch,
            focused.get(),
            review.get(),
            cancelled,
            hotfix.publication().reviewBranchUrl(),
            progressReporter
        ).map(resource -> withPatchInstruction(resource, hotfix.patchInstruction()));
    }

    private Either<IncidentFailure, HotfixResource> executePrepared(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        Workspace workspace,
        HotfixResource.PatchInstruction patchInstruction,
        Consumer<ProgressUpdate> progressReporter,
        BooleanSupplier cancelled
    ) {
        var patchResult = createVerifiedPatch(
            candidate, workspace, patchInstruction, progressReporter, cancelled
        );
        if (patchResult.isLeft()) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, workspace, null,
                failure(patchFailureStage(patchResult.getLeft()), patchResult.getLeft())
            ));
        }
        if (patchResult.get().failure() != null) {
            return Either.right(needsReview(
                analysis,
                candidate,
                hotfixId,
                patchResult.get().patch().workspace(),
                patchResult.get().focusedVerification(),
                failure(WorkflowStage.FOCUSED_VERIFICATION, patchResult.get().failure())
            ));
        }
        AppliedPatch patch = patchResult.get().patch();
        Verification focusedVerification = patchResult.get().focusedVerification();
        if (cancelled.getAsBoolean()) {
            return Either.left(cancellationFailure());
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.VERIFYING,
            patch.workspace().branchName(),
            WorkflowStage.CODE_REVIEW,
            "집중 테스트를 통과한 패치를 독립 AI reviewer가 검토하고 있습니다."
        ));
        var reviewResult = patchReview.review(candidate, patch);
        if (reviewResult.isLeft() || !reviewResult.get().approved()) {
            String reason = reviewResult.isLeft()
                ? reviewResult.getLeft().message() : reviewResult.get().summary();
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(), focusedVerification,
                failure(WorkflowStage.CODE_REVIEW, "PATCH_REVIEW_REJECTED", reason)
            ));
        }
        return verifyAndPublish(
            analysis,
            candidate,
            hotfixId,
            patch,
            focusedVerification,
            reviewResult.get(),
            cancelled,
            null,
            progressReporter
        );
    }

    private Either<IncidentFailure, VerifiedPatch> createVerifiedPatch(
        BugCandidate candidate,
        Workspace initialWorkspace,
        HotfixResource.PatchInstruction patchInstruction,
        Consumer<ProgressUpdate> progressReporter,
        BooleanSupplier cancelled
    ) {
        Workspace workspace = initialWorkspace;
        String previousFailure = "none";
        int maximumAttempts = parityProperties.limits().maxPatchRetries() + 1;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            if (cancelled.getAsBoolean()) {
                return Either.left(cancellationFailure());
            }
            var proposal = patchProposal.propose(new PatchProposalPort.PatchRequest(
                candidate, workspace, attempt, previousFailure, patchInstruction
            ));
            if (proposal.isLeft()) {
                return Either.left(proposal.getLeft());
            }
            var applied = patchWorkspace.apply(workspace, proposal.get());
            if (applied.isLeft()) {
                return Either.left(applied.getLeft());
            }
            progressReporter.accept(new ProgressUpdate(
                HotfixResource.Status.VERIFYING,
                applied.get().workspace().branchName(),
                WorkflowStage.FOCUSED_VERIFICATION,
                "변경 모듈의 집중 빌드·테스트를 실행하고 있습니다. 시도 " + attempt
                    + '/' + maximumAttempts
            ));
            var focused = verification.runFocused(applied.get(), attempt);
            if (focused.isLeft()) {
                return Either.left(focused.getLeft());
            }
            if (passed(focused.get())) {
                return Either.right(new VerifiedPatch(applied.get(), focused.get(), null));
            }
            previousFailure = "Focused verification failed at attempt " + attempt;
            if (attempt == maximumAttempts) {
                return Either.right(new VerifiedPatch(
                    applied.get(),
                    focused.get(),
                    new IncidentFailure(
                        "PATCH_RETRY_EXHAUSTED",
                        "집중 검증이 수정 재시도 한도를 초과했습니다."
                    )
                ));
            }
            if (attempt < maximumAttempts) {
                var refreshed = patchWorkspace.refresh(applied.get().workspace());
                if (refreshed.isLeft()) {
                    return Either.left(refreshed.getLeft());
                }
                workspace = refreshed.get();
            }
        }
        throw new IllegalStateException("Patch attempt loop completed without a result");
    }

    private Either<IncidentFailure, HotfixResource> verifyAndPublish(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        AppliedPatch patch,
        Verification focusedVerification,
        Review review,
        BooleanSupplier cancelled,
        String reviewBranchUrl,
        Consumer<ProgressUpdate> progressReporter
    ) {
        if (cancelled.getAsBoolean()) {
            return Either.left(cancellationFailure());
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.VERIFYING,
            patch.workspace().branchName(),
            WorkflowStage.PARITY_VERIFICATION,
            "Jenkinsfile 승인 profile과 동일한 로컬 빌드·테스트를 실행하고 있습니다."
        ));
        var parity = verification.runParity(patch, focusedVerification.focusedAttempts());
        if (parity.isLeft() || !passed(parity.get())) {
            String reason = parity.isLeft()
                ? parity.getLeft().message() : "Jenkins parity 검증 단계가 실패했습니다.";
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(),
                parity.getOrElse(focusedVerification),
                failure(WorkflowStage.PARITY_VERIFICATION, "PARITY_VERIFICATION_FAILED", reason),
                reviewPublication(reviewBranchUrl)
            ));
        }
        var currentHead = patchWorkspace.currentHead(patch.workspace());
        if (currentHead.isLeft() || !patch.patchCommit().equals(currentHead.get())
            || !patch.patchCommit().equals(parity.get().provenance().patchCommit())) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(), parity.get(),
                failure(
                    WorkflowStage.PARITY_VERIFICATION,
                    "PATCH_COMMIT_CHANGED",
                    "검증 이후 커밋이 변경되어 Draft PR 생성을 차단했습니다."
                ),
                reviewPublication(reviewBranchUrl)
            ));
        }
        if (cancelled.getAsBoolean()) {
            return Either.left(cancellationFailure());
        }
        progressReporter.accept(new ProgressUpdate(
            HotfixResource.Status.VERIFYING,
            patch.workspace().branchName(),
            WorkflowStage.DRAFT_PR_PUBLICATION,
            "검증한 commit을 Bitbucket Draft PR로 게시하고 있습니다."
        ));
        var publication = pullRequest.publishDraft(new PullRequestPort.PublicationRequest(
            hotfixId,
            new IncidentArtifact(analysis, candidate),
            new PatchArtifact(patch, parity.get(), review)
        ));
        if (publication.isLeft()) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(), parity.get(),
                failure(WorkflowStage.DRAFT_PR_PUBLICATION, publication.getLeft()),
                reviewPublication(reviewBranchUrl)
            ));
        }
        return Either.right(created(
            analysis, candidate, hotfixId, patch, parity.get(), publication.get(), reviewBranchUrl
        ));
    }

    private boolean passed(Verification verificationResult) {
        return !verificationResult.stages().isEmpty()
            && verificationResult.stages().stream()
            .filter(HotfixResource.StageResult::required)
            .allMatch(stage -> stage.exitCode() == 0);
    }

    private IncidentFailure cancellationFailure() {
        return new IncidentFailure("HOTFIX_CANCELLED", "사용자가 hotfix 작업을 취소했습니다.");
    }

    private HotfixResource created(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        AppliedPatch patch,
        Verification verificationResult,
        Publication publication,
        String reviewBranchUrl
    ) {
        return new HotfixResource(
            identity(analysis, candidate, hotfixId),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.DRAFT_PR_CREATED,
                    patch.workspace().branchName(),
                    new HotfixResource.ExecutionDetail(
                        WorkflowStage.CI,
                        "Draft PR Jenkins CI 결과를 기다리고 있습니다."
                    ),
                    null
                ),
                new ChangeMetrics(
                    patch.changes().files().size(),
                    patch.changes().changedLines()
                ),
                verificationResult
            ),
            new HotfixResource.Publication(
                reviewBranchUrl, publication.pullRequestUrl(), publication.ciJobUrl(), "PENDING"
            )
        );
    }

    private HotfixResource needsReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        Workspace workspace,
        Verification verificationResult,
        FailureDetail failure
    ) {
        return needsReview(
            analysis, candidate, hotfixId, workspace, verificationResult, failure,
            HotfixResource.Publication.empty()
        );
    }

    private HotfixResource needsReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        Workspace workspace,
        Verification verificationResult,
        FailureDetail failure,
        HotfixResource.Publication publication
    ) {
        return new HotfixResource(
            identity(analysis, candidate, hotfixId),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                    workspace == null ? null : workspace.branchName(),
                    null,
                    failure
                ),
                ChangeMetrics.empty(),
                verificationResult == null ? Verification.empty() : verificationResult
            ),
            publication
        );
    }

    private HotfixResource.Publication reviewPublication(String reviewBranchUrl) {
        return HotfixResource.Publication.forHumanReview(reviewBranchUrl);
    }

    private HotfixResource humanChangesNeedReview(
        HotfixResource hotfix,
        Verification verificationResult,
        FailureDetail failure
    ) {
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                hotfix.progress().branchName(),
                null,
                failure
            ),
            hotfix.progress().changes(),
            verificationResult
        );
        return new HotfixResource(
            hotfix.identity(), hotfix.patchInstruction(), progress, hotfix.publication()
        );
    }

    private HotfixResource withPatchInstruction(
        HotfixResource resource,
        HotfixResource.PatchInstruction patchInstruction
    ) {
        return new HotfixResource(
            resource.identity(), patchInstruction, resource.progress(), resource.publication()
        );
    }

    private FailureDetail failure(WorkflowStage stage, IncidentFailure failure) {
        return failure(stage, failure.code(), failure.message());
    }

    private FailureDetail failure(WorkflowStage stage, String code, String message) {
        return new FailureDetail(stage, code, message);
    }

    private WorkflowStage patchFailureStage(IncidentFailure failure) {
        return failure.code().contains("VERIFICATION")
            || failure.code().contains("RETRY")
            ? WorkflowStage.FOCUSED_VERIFICATION : WorkflowStage.PATCH_GENERATION;
    }

    private HotfixResource.Identity identity(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId
    ) {
        return new HotfixResource.Identity(
            hotfixId,
            analysis.identity().analysisId(),
            candidate.identity().candidateId()
        );
    }

    private record VerifiedPatch(
        AppliedPatch patch,
        Verification focusedVerification,
        IncidentFailure failure
    ) {
    }
}
