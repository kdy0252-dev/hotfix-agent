package com.example.myagent.incident.adapter.out.workflow;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.global.configuration.ParityProfileProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
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
        String hotfixId
    ) {
        if (runtimeProperties.mode() != AgentRuntimeProperties.Mode.DRAFT_PR) {
            return Either.right(needsReview(
                analysis,
                candidate,
                hotfixId,
                null,
                null,
                "AGENT_MODE가 DRAFT_PR이 아니므로 코드 변경을 실행하지 않았습니다."
            ));
        }
        var prepared = patchWorkspace.prepare(analysis, candidate, hotfixId);
        if (prepared.isLeft()) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, null, null, prepared.getLeft().message()
            ));
        }
        return executePrepared(analysis, candidate, hotfixId, prepared.get());
    }

    private Either<IncidentFailure, HotfixResource> executePrepared(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        Workspace workspace
    ) {
        var patchResult = createVerifiedPatch(candidate, workspace);
        if (patchResult.isLeft()) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, workspace, null, patchResult.getLeft().message()
            ));
        }
        AppliedPatch patch = patchResult.get().patch();
        Verification focusedVerification = patchResult.get().focusedVerification();
        var reviewResult = patchReview.review(candidate, patch);
        if (reviewResult.isLeft() || !reviewResult.get().approved()) {
            String reason = reviewResult.isLeft()
                ? reviewResult.getLeft().message() : reviewResult.get().summary();
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(), focusedVerification, reason
            ));
        }
        return verifyAndPublish(
            analysis,
            candidate,
            hotfixId,
            patch,
            focusedVerification,
            reviewResult.get()
        );
    }

    private Either<IncidentFailure, VerifiedPatch> createVerifiedPatch(
        BugCandidate candidate,
        Workspace initialWorkspace
    ) {
        Workspace workspace = initialWorkspace;
        String previousFailure = "none";
        int maximumAttempts = parityProperties.maxPatchRetries() + 1;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            var proposal = patchProposal.propose(new PatchProposalPort.PatchRequest(
                candidate, workspace, attempt, previousFailure
            ));
            if (proposal.isLeft()) {
                return Either.left(proposal.getLeft());
            }
            var applied = patchWorkspace.apply(workspace, proposal.get());
            if (applied.isLeft()) {
                return Either.left(applied.getLeft());
            }
            var focused = verification.runFocused(applied.get(), attempt);
            if (focused.isLeft()) {
                return Either.left(focused.getLeft());
            }
            if (passed(focused.get())) {
                return Either.right(new VerifiedPatch(applied.get(), focused.get()));
            }
            previousFailure = "Focused verification failed at attempt " + attempt;
            if (attempt < maximumAttempts) {
                var refreshed = patchWorkspace.refresh(applied.get().workspace());
                if (refreshed.isLeft()) {
                    return Either.left(refreshed.getLeft());
                }
                workspace = refreshed.get();
            }
        }
        return Either.left(new IncidentFailure(
            "PATCH_RETRY_EXHAUSTED",
            "집중 검증이 수정 재시도 한도를 초과했습니다."
        ));
    }

    private Either<IncidentFailure, HotfixResource> verifyAndPublish(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        AppliedPatch patch,
        Verification focusedVerification,
        Review review
    ) {
        var parity = verification.runParity(patch, focusedVerification.focusedAttempts());
        if (parity.isLeft() || !passed(parity.get())) {
            String reason = parity.isLeft()
                ? parity.getLeft().message() : "Jenkins parity 검증 단계가 실패했습니다.";
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(),
                parity.getOrElse(focusedVerification), reason
            ));
        }
        var currentHead = patchWorkspace.currentHead(patch.workspace());
        if (currentHead.isLeft() || !patch.patchCommit().equals(currentHead.get())
            || !patch.patchCommit().equals(parity.get().provenance().patchCommit())) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(), parity.get(),
                "검증 이후 커밋이 변경되어 Draft PR 생성을 차단했습니다."
            ));
        }
        var publication = pullRequest.publishDraft(new PullRequestPort.PublicationRequest(
            hotfixId,
            new IncidentArtifact(analysis, candidate),
            new PatchArtifact(patch, parity.get(), review)
        ));
        if (publication.isLeft()) {
            return Either.right(needsReview(
                analysis, candidate, hotfixId, patch.workspace(), parity.get(),
                publication.getLeft().message()
            ));
        }
        return Either.right(created(analysis, candidate, hotfixId, patch, parity.get(), publication.get()));
    }

    private boolean passed(Verification verificationResult) {
        return !verificationResult.stages().isEmpty()
            && verificationResult.stages().stream()
            .filter(HotfixResource.StageResult::required)
            .allMatch(stage -> stage.exitCode() == 0);
    }

    private HotfixResource created(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        AppliedPatch patch,
        Verification verificationResult,
        Publication publication
    ) {
        return new HotfixResource(
            identity(analysis, candidate, hotfixId),
            new HotfixResource.Progress(
                HotfixResource.Status.DRAFT_PR_CREATED,
                patch.workspace().branchName(),
                patch.changes().files().size(),
                patch.changes().changedLines(),
                verificationResult,
                null
            ),
            new HotfixResource.Publication(
                publication.pullRequestUrl(), publication.ciJobUrl(), "PENDING"
            )
        );
    }

    private HotfixResource needsReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        Workspace workspace,
        Verification verificationResult,
        String reason
    ) {
        return new HotfixResource(
            identity(analysis, candidate, hotfixId),
            new HotfixResource.Progress(
                HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                workspace == null ? null : workspace.branchName(),
                0,
                0,
                verificationResult == null
                    ? Verification.empty() : verificationResult,
                reason
            ),
            new HotfixResource.Publication(null, null, null)
        );
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

    private record VerifiedPatch(AppliedPatch patch, Verification focusedVerification) {
    }
}
