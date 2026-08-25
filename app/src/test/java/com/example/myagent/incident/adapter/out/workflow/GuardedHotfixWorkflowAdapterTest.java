package com.example.myagent.incident.adapter.out.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.global.configuration.ParityProfileProperties;
import com.example.myagent.global.configuration.ParityProfileProperties.ExecutionLimits;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.JenkinsfileProfile;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.StageResult;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.VerificationProvenance;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.ChangeSummary;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.FileUpdate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Publication;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Review;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.PatchProposalPort;
import com.example.myagent.incident.application.port.out.PatchReviewPort;
import com.example.myagent.incident.application.port.out.PatchWorkspacePort;
import com.example.myagent.incident.application.port.out.PullRequestPort;
import com.example.myagent.incident.application.port.out.VerificationPort;
import io.vavr.control.Either;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GuardedHotfixWorkflowAdapterTest {
    private static final String HOTFIX_ID = "12345678-1234-1234-1234-123456789012";
    private static final String PATCH_COMMIT = "patch123";

    private final PatchProposalPort proposalPort = mock(PatchProposalPort.class);
    private final PatchWorkspacePort workspacePort = mock(PatchWorkspacePort.class);
    private final VerificationPort verificationPort = mock(VerificationPort.class);
    private final PatchReviewPort reviewPort = mock(PatchReviewPort.class);
    private final PullRequestPort pullRequestPort = mock(PullRequestPort.class);
    private GuardedHotfixWorkflowAdapter workflow;

    @BeforeEach
    void setUp() {
        workflow = new GuardedHotfixWorkflowAdapter(
            proposalPort,
            workspacePort,
            verificationPort,
            reviewPort,
            pullRequestPort,
            new ParityProfileProperties(
                Map.of("jenkins-hash", 1),
                new ExecutionLimits(2, 2),
                Path.of(".agent/runtime")
            ),
            new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.DRAFT_PR,
                Path.of("/tmp/fms"),
                Duration.ofHours(24)
            )
        );
    }

    @Test
    void publishesDraftOnlyAfterFocusedReviewParityAndCommitIdentityPass() {
        Workspace workspace = workspace();
        AppliedPatch patch = patch(workspace);
        when(workspacePort.prepare(any(), any(), any())).thenReturn(Either.right(workspace));
        when(proposalPort.propose(any())).thenReturn(Either.right(proposal()));
        when(workspacePort.apply(workspace, proposal())).thenReturn(Either.right(patch));
        when(verificationPort.runFocused(patch, 1)).thenReturn(Either.right(focused(true)));
        when(reviewPort.review(candidate(), patch)).thenReturn(Either.right(approvedReview()));
        when(verificationPort.runParity(patch, 1)).thenReturn(Either.right(parity(true)));
        when(workspacePort.currentHead(workspace)).thenReturn(Either.right(PATCH_COMMIT));
        when(pullRequestPort.publishDraft(any())).thenReturn(Either.right(new Publication(
            "https://bitbucket.example/pr/1",
            "https://jenkins.example/job/PR-1/"
        )));

        var progressUpdates = new ArrayList<HotfixWorkflowPort.ProgressUpdate>();
        HotfixResource result = workflow.execute(
            analysis(),
            candidate(),
            selectedHotfix("중복 요청이면 기존 결과를 반환하도록 수정"),
            progressUpdates::add,
            () -> false
        ).get();

        assertThat(result.progress().status()).isEqualTo(HotfixResource.Status.DRAFT_PR_CREATED);
        assertThat(result.progress().verification().provenance().patchCommit())
            .isEqualTo(PATCH_COMMIT);
        assertThat(result.publication().draftPullRequestUrl())
            .isEqualTo("https://bitbucket.example/pr/1");
        assertThat(progressUpdates).extracting(HotfixWorkflowPort.ProgressUpdate::stage)
            .containsExactly(
                HotfixResource.WorkflowStage.WORKSPACE_PREPARATION,
                HotfixResource.WorkflowStage.PATCH_GENERATION,
                HotfixResource.WorkflowStage.FOCUSED_VERIFICATION,
                HotfixResource.WorkflowStage.CODE_REVIEW,
                HotfixResource.WorkflowStage.PARITY_VERIFICATION,
                HotfixResource.WorkflowStage.DRAFT_PR_PUBLICATION
            );
        verify(pullRequestPort).publishDraft(any());
        var patchRequest = ArgumentCaptor.forClass(PatchProposalPort.PatchRequest.class);
        verify(proposalPort).propose(patchRequest.capture());
        assertThat(patchRequest.getValue().patchInstruction().text())
            .isEqualTo("중복 요청이면 기존 결과를 반환하도록 수정");
    }

    @Test
    void blocksDraftWhenJenkinsParityFails() {
        Workspace workspace = workspace();
        AppliedPatch patch = patch(workspace);
        when(workspacePort.prepare(any(), any(), any())).thenReturn(Either.right(workspace));
        when(proposalPort.propose(any())).thenReturn(Either.right(proposal()));
        when(workspacePort.apply(workspace, proposal())).thenReturn(Either.right(patch));
        when(verificationPort.runFocused(patch, 1)).thenReturn(Either.right(focused(true)));
        when(reviewPort.review(candidate(), patch)).thenReturn(Either.right(approvedReview()));
        when(verificationPort.runParity(patch, 1)).thenReturn(Either.right(parity(false)));

        HotfixResource result = workflow.execute(
            analysis(),
            candidate(),
            selectedHotfix(),
            progressUpdate -> { },
            () -> false
        ).get();

        assertThat(result.progress().status()).isEqualTo(HotfixResource.Status.NEEDS_HUMAN_REVIEW);
        assertThat(result.progress().humanReviewReason()).contains("parity");
        verify(pullRequestPort, never()).publishDraft(any());
    }

    @Test
    void retainsTheLastFailedVerificationForHumanDiagnosis() {
        Workspace workspace = workspace();
        AppliedPatch patch = patch(workspace);
        when(workspacePort.prepare(any(), any(), any())).thenReturn(Either.right(workspace));
        when(proposalPort.propose(any())).thenReturn(Either.right(proposal()));
        when(workspacePort.apply(any(), any())).thenReturn(Either.right(patch));
        when(verificationPort.runFocused(any(), any(Integer.class)))
            .thenAnswer(invocation -> Either.right(focused(false)));
        when(workspacePort.refresh(any())).thenReturn(Either.right(workspace));

        HotfixResource result = workflow.execute(
            analysis(), candidate(), selectedHotfix(), progressUpdate -> { }, () -> false
        ).get();

        assertThat(result.progress().failure().stage())
            .isEqualTo(HotfixResource.WorkflowStage.FOCUSED_VERIFICATION);
        assertThat(result.progress().verification().stages())
            .extracting(StageResult::summary)
            .containsExactly("focused");
        verify(pullRequestPort, never()).publishDraft(any());
    }

    @Test
    void doesNotPublishADraftAfterCancellation() {
        var result = workflow.execute(
            analysis(),
            candidate(),
            selectedHotfix(),
            progressUpdate -> { },
            () -> true
        );

        assertThat(result.getLeft().code()).isEqualTo("HOTFIX_CANCELLED");
        verify(pullRequestPort, never()).publishDraft(any());
    }

    @Test
    void publishesAnExistingAgentBranchForHumanReview() {
        var hotfix = needsReviewHotfix(null);
        when(workspacePort.publishForHumanReview(
            analysis(), candidate(), HOTFIX_ID, workspace().branchName()
        )).thenReturn(Either.right(new PatchWorkspacePort.ReviewBranch(
            workspace().branchName(),
            "https://bitbucket.example/branch/agent-hotfix",
            PATCH_COMMIT
        )));

        HotfixResource result = workflow.publishForHumanReview(
            analysis(), candidate(), hotfix
        ).get();

        assertThat(result.publication().reviewBranchUrl())
            .isEqualTo("https://bitbucket.example/branch/agent-hotfix");
        assertThat(result.progress()).isEqualTo(hotfix.progress());
    }

    @Test
    void reloadsAHumanCommitAndRunsEveryGateBeforePublishingDraft() {
        var hotfix = needsReviewHotfix("https://bitbucket.example/branch/agent-hotfix");
        AppliedPatch patch = patch(workspace());
        when(workspacePort.reloadHumanChanges(
            analysis(), candidate(), HOTFIX_ID, workspace().branchName()
        )).thenReturn(Either.right(patch));
        when(verificationPort.runFocused(patch, 1)).thenReturn(Either.right(focused(true)));
        when(reviewPort.review(candidate(), patch)).thenReturn(Either.right(approvedReview()));
        when(verificationPort.runParity(patch, 1)).thenReturn(Either.right(parity(true)));
        when(workspacePort.currentHead(workspace())).thenReturn(Either.right(PATCH_COMMIT));
        when(pullRequestPort.publishDraft(any())).thenReturn(Either.right(new Publication(
            "https://bitbucket.example/pr/1",
            "https://jenkins.example/job/PR-1/"
        )));

        HotfixResource result = workflow.verifyHumanChanges(
            analysis(), candidate(), hotfix, progressUpdate -> { }, () -> false
        ).get();

        assertThat(result.progress().status()).isEqualTo(HotfixResource.Status.DRAFT_PR_CREATED);
        assertThat(result.publication().reviewBranchUrl())
            .isEqualTo("https://bitbucket.example/branch/agent-hotfix");
        verify(verificationPort).runFocused(patch, 1);
        verify(reviewPort).review(candidate(), patch);
        verify(verificationPort).runParity(patch, 1);
        verify(pullRequestPort).publishDraft(any());
    }

    private AnalysisSession analysis() {
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "request-hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                new SourceRevision("base123", "main", "bitbucket:branch:main"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate()),
                null
            )
        );
    }

    private BugCandidate candidate() {
        return new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Null booking response",
                "BookingService dereferences a null response",
                0.95,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of("eu/eu-app/src/main/java/BookingService.java:84"),
                List.of("jenkins:181:console"),
                List.of()
            ),
            new BugCandidate.Recommendation("Guard response", "Run parity")
        );
    }

    private Workspace workspace() {
        return new Workspace(
            "/tmp/worktree",
            "agent/hotfix/12345678-null-booking-response",
            "base123",
            Map.of("eu/eu-app/src/main/java/BookingService.java", "class BookingService {}")
        );
    }

    private Proposal proposal() {
        return new Proposal(
            "Guard response",
            List.of(new FileUpdate(
                "eu/eu-app/src/main/java/BookingService.java",
                "class BookingService { boolean safe; }",
                "Guard response"
            ))
        );
    }

    private AppliedPatch patch(Workspace workspace) {
        return new AppliedPatch(
            workspace,
            new ChangeSummary(
                List.of("eu/eu-app/src/main/java/BookingService.java"),
                2
            ),
            PATCH_COMMIT
        );
    }

    private Verification focused(boolean passed) {
        return Verification.focused(
            1,
            "base123",
            PATCH_COMMIT,
            List.of(new StageResult("focused-gradle", passed ? 0 : 1, true, "focused"))
        );
    }

    private Verification parity(boolean passed) {
        return new Verification(
            1,
            new VerificationProvenance(
                "base123",
                PATCH_COMMIT,
                new JenkinsfileProfile("eu/Jenkinsfile", "jenkins-hash", 1)
            ),
            List.of(new StageResult(
                "jenkins-gradle-verification",
                passed ? 0 : 1,
                true,
                "parity"
            ))
        );
    }

    private Review approvedReview() {
        return new Review(true, "Approved", List.of());
    }

    private HotfixResource needsReviewHotfix(String reviewBranchUrl) {
        return new HotfixResource(
            new HotfixResource.Identity(HOTFIX_ID, "analysis-1", "candidate-1"),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                    workspace().branchName(),
                    null,
                    new HotfixResource.FailureDetail(
                        HotfixResource.WorkflowStage.CODE_REVIEW,
                        "PATCH_REVIEW_REJECTED",
                        "사람 검토 필요"
                    )
                ),
                HotfixResource.ChangeMetrics.empty(),
                Verification.empty()
            ),
            HotfixResource.Publication.forHumanReview(reviewBranchUrl)
        );
    }

    private HotfixResource selectedHotfix() {
        return selectedHotfix("");
    }

    private HotfixResource selectedHotfix(String patchInstruction) {
        return new HotfixResource(
            new HotfixResource.Identity(HOTFIX_ID, "analysis-1", "candidate-1"),
            HotfixResource.PatchInstruction.from(patchInstruction),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.SELECTED,
                    null,
                    null,
                    null
                ),
                HotfixResource.ChangeMetrics.empty(),
                Verification.empty()
            ),
            HotfixResource.Publication.empty()
        );
    }
}
