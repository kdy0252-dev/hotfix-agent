package com.example.myagent.dashboard.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.dashboard.application.port.in.DashboardUseCase;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import jakarta.validation.Validation;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

class DashboardControllerTest {
    private MockMvc mockMvc;
    private DashboardUseCase dashboardUseCase;

    @BeforeEach
    void setUp() {
        dashboardUseCase = mock(DashboardUseCase.class);
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var observabilityScope = new ObservabilityScopeProperties(
            "eu",
            "app",
            "Booking API",
            "fms-eu-%s",
            "fms-eu-%s-app"
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DashboardController(dashboardUseCase, observabilityScope)
            )
            .setControllerAdvice(new DashboardExceptionHandler())
            .setValidator(new SpringValidatorAdapter(validator))
            .build();
    }

    @Test
    void rendersTheDashboardWithoutQueryingExternalSystems() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/index"))
            .andExpect(model().attributeExists(
                "startAt",
                "endAt",
                "commandIdempotencyKey",
                "observabilityServiceName"
            ));
    }

    @Test
    void redirectsTheLegacyUiPathToTheMainPage() throws Exception {
        mockMvc.perform(get("/ui"))
            .andExpect(status().is3xxRedirection())
            .andExpect(view().name("redirect:/"));
    }

    @Test
    void rendersFailedPullRequestsWithBitbucketAndJenkinsReferences() throws Exception {
        var failedPullRequest = failedPullRequest();
        when(dashboardUseCase.getFailedPullRequests()).thenReturn(List.of(failedPullRequest));
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(completedWorkflow()));

        mockMvc.perform(get("/ui/fragments/pull-requests"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/pull-requests"))
            .andExpect(model().attribute("pullRequests", List.of(failedPullRequest)))
            .andExpect(model().attribute("duplicatePullRequestIds", Set.of(1292L)));
    }

    @Test
    void requestsJenkinsAnalysisDirectlyFromTheFailedPullRequestList() throws Exception {
        var execution = new DashboardView.ExecutionResult(
            "analysis-1",
            "ANALYSIS_REQUESTED",
            "/api/v1/analyses/analysis-1",
            List.of()
        );
        when(dashboardUseCase.requestJenkinsAnalysis(any())).thenReturn(execution);
        when(dashboardUseCase.getAnalysis("analysis-1")).thenReturn(analysis("ANALYZING"));

        mockMvc.perform(post("/ui/analyses/jenkins")
                .param("jobPath", "FMS-EU/job/PR-1292")
                .param("buildNumber", "1")
                .param("pullRequestId", "1292")
                .param("idempotencyKey", "analysis-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/analysis-action"))
            .andExpect(model().attribute("analysisCompleted", false))
            .andExpect(model().attribute("duplicateRequest", false))
            .andExpect(model().attribute("analysisId", "analysis-1"));

        verify(dashboardUseCase).requestJenkinsAnalysis(any());
    }

    @Test
    void marksACompletedDuplicateAsStoredAndAllowsAForcedRequest() throws Exception {
        var storedExecution = new DashboardView.ExecutionResult(
            "analysis-1",
            "CANDIDATES_READY",
            "/api/v1/analyses/analysis-1",
            List.of()
        );
        var requestedExecution = new DashboardView.ExecutionResult(
            "analysis-2",
            "ANALYSIS_REQUESTED",
            "/api/v1/analyses/analysis-2",
            List.of()
        );
        when(dashboardUseCase.requestJenkinsAnalysis(any()))
            .thenReturn(storedExecution, requestedExecution);
        when(dashboardUseCase.getAnalysis("analysis-1"))
            .thenReturn(analysis("CANDIDATES_READY"));
        when(dashboardUseCase.getAnalysis("analysis-2"))
            .thenReturn(analysis("ANALYZING"));

        mockMvc.perform(post("/ui/analyses/jenkins")
                .param("jobPath", "FMS-EU/job/PR-1292")
                .param("buildNumber", "1")
                .param("pullRequestId", "1292")
                .param("idempotencyKey", "stable-analysis-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/analysis-action"))
            .andExpect(model().attribute("analysisCompleted", true))
            .andExpect(model().attribute("duplicateRequest", true));

        mockMvc.perform(post("/ui/analyses/jenkins")
                .param("jobPath", "FMS-EU/job/PR-1292")
                .param("buildNumber", "1")
                .param("pullRequestId", "1292")
                .param("idempotencyKey", "stable-analysis-key")
                .param("force", "true"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("analysisCompleted", false))
            .andExpect(model().attribute("duplicateRequest", false));
    }

    @Test
    void pollsOnlyTheRequestedAnalysisActionUntilItCompletes() throws Exception {
        when(dashboardUseCase.getAnalysis("analysis-1"))
            .thenReturn(analysis("CANDIDATES_READY"));

        mockMvc.perform(get("/ui/fragments/analyses/analysis-1/action")
                .param("jobPath", "FMS-EU/job/PR-1292")
                .param("buildNumber", "1")
                .param("pullRequestId", "1292")
                .param("idempotencyKey", "analysis-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/analysis-action"))
            .andExpect(model().attribute("analysisCompleted", true));
    }

    @Test
    void rendersUnifiedWorkflowsFromDatabase() throws Exception {
        var workflow = workflowWithCandidate();
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(workflow));

        mockMvc.perform(get("/ui/fragments/workflows"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"))
            .andExpect(model().attribute("workflows", List.of(workflow)))
            .andExpect(model().attribute(
                "workflowBranches",
                List.of("feature/compile-failure-test")
            ));
    }

    @Test
    void filtersUnifiedWorkflowsByPullRequestBranchOnTheServer() throws Exception {
        var mainWorkflow = workflowWithCandidate("main", "main");
        var pullRequestWorkflow = workflowWithCandidate(
            "PR-1301",
            "feature/compile-failure-test"
        );
        when(dashboardUseCase.getWorkflowItems())
            .thenReturn(List.of(mainWorkflow, pullRequestWorkflow));

        mockMvc.perform(get("/ui/fragments/workflows")
                .param("branch", "feature/compile-failure-test"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"))
            .andExpect(model().attribute("workflows", List.of(pullRequestWorkflow)))
            .andExpect(model().attribute(
                "selectedWorkflowBranch",
                "feature/compile-failure-test"
            ));
    }

    @Test
    void rendersOnlyOneWorkflowCardForTargetedPolling() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(get("/ui/fragments/workflows/analysis-1"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows :: card"))
            .andExpect(model().attribute("workflows", List.of()));
    }

    @Test
    void cancelsAndDeletesAStoredWorkflow() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(delete("/ui/workflows/analysis-1"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"));

        verify(dashboardUseCase).cancelAndDeleteWorkflow("analysis-1");
    }

    @Test
    void cancelsAndDeletesOneAtomicHotfix() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(delete("/ui/hotfixes/hotfix-1"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"));

        verify(dashboardUseCase).cancelAndDeleteHotfix("hotfix-1");
    }

    @Test
    void restartsOneAtomicHotfixThroughTheGuardedWorkflow() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(post("/ui/hotfixes/hotfix-1/restarts"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"));

        verify(dashboardUseCase).restartHotfix(any());
    }

    @Test
    void publishesTheExistingHotfixBranchForHumanReview() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(post("/ui/hotfixes/hotfix-1/human-review-branch"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"));

        verify(dashboardUseCase).publishHumanReviewBranch("hotfix-1");
    }

    @Test
    void reloadsAndVerifiesTheHumanCommit() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(post("/ui/hotfixes/hotfix-1/human-changes-verification"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"));

        verify(dashboardUseCase).verifyHumanChanges("hotfix-1");
    }

    @Test
    void refreshesJenkinsCiBeforeRenderingTheWorkflow() throws Exception {
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of());

        mockMvc.perform(post("/ui/hotfixes/hotfix-1/ci-refresh"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows"));

        verify(dashboardUseCase).refreshHotfixCi("hotfix-1");
    }

    @Test
    void interpretsNaturalLanguageBeforeAnyExecution() throws Exception {
        var preview = new DashboardView.InterpretationPreview(
            new DashboardView.Metadata(
                "interpretation-1",
                1,
                Instant.parse("2026-08-24T01:00:00Z")
            ),
            new DashboardView.Decision(
                "READY_FOR_CONFIRMATION",
                "LIST_CANDIDATES",
                "PR-1292 build 1",
                List.of(),
                null,
                "command-hash"
            )
        );
        when(dashboardUseCase.interpretNaturalLanguage(any())).thenReturn(preview);

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "PR-1292의 실패 빌드를 분석해줘")
                .param("idempotencyKey", "command-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/interpretation"))
            .andExpect(model().attribute("interpretation", preview))
            .andExpect(model().attributeExists("executionIdempotencyKey"));

        verify(dashboardUseCase).interpretNaturalLanguage(any());
    }

    @Test
    void carriesTheLatestFailedPullRequestContextIntoAFollowUpMessage() throws Exception {
        var preview = confirmablePreview();
        when(dashboardUseCase.interpretNaturalLanguage(any())).thenReturn(preview);
        when(dashboardUseCase.executeNaturalLanguage(any())).thenReturn(
            new DashboardView.ExecutionResult(
                "analysis-1",
                "ANALYSIS_REQUESTED",
                "/api/v1/analyses/analysis-1",
                List.of()
            )
        );
        when(dashboardUseCase.getAnalysis("analysis-1")).thenReturn(analysis("ANALYZING"));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "그럼 그거 이슈 분석좀 해줄래?")
                .param("conversationContext", "LATEST_FAILED_PULL_REQUEST")
                .param("idempotencyKey", "follow-up-key"))
            .andExpect(status().isOk());

        var command = ArgumentCaptor.forClass(DashboardUseCase.InterpretationCommand.class);
        verify(dashboardUseCase).interpretNaturalLanguage(command.capture());
        assertThat(command.getValue().text())
            .isEqualTo("최근 실패 빌드 그럼 그거 이슈 분석좀 해줄래?");
    }

    @Test
    void immediatelyStartsJenkinsAnalysisInsideTheChatWithoutAConfirmationCard()
        throws Exception {
        var preview = confirmablePreview();
        when(dashboardUseCase.interpretNaturalLanguage(any())).thenReturn(preview);
        when(dashboardUseCase.executeNaturalLanguage(any())).thenReturn(
            new DashboardView.ExecutionResult(
                "analysis-1",
                "ANALYSIS_REQUESTED",
                "/api/v1/analyses/analysis-1",
                List.of()
            )
        );
        when(dashboardUseCase.getAnalysis("analysis-1")).thenReturn(analysis("ANALYZING"));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param(
                    "text",
                    "최근 빌드 실패한거 PR-1301 분석 후에 드래프트 PR까지 생성해줄래?"
                )
                .param("idempotencyKey", "command-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-analysis"))
            .andExpect(model().attribute("analysis", analysis("ANALYZING")));

        verify(dashboardUseCase).executeNaturalLanguage(any());
    }

    @Test
    void startsObservabilityAnalysisAgainstTheSelectedSource() throws Exception {
        when(dashboardUseCase.requestObservabilityAnalysis(any())).thenReturn(
            new DashboardView.ExecutionResult(
                "analysis-trace",
                "ANALYSIS_REQUESTED",
                "/api/v1/analyses/analysis-trace",
                List.of()
            )
        );
        when(dashboardUseCase.getAnalysis("analysis-trace")).thenReturn(analysis("ANALYZING"));

        mockMvc.perform(post("/ui/analyses/observability")
                .param("signalKey", "log-1787539541000")
                .param("startAt", "2026-08-24T03:35")
                .param("endAt", "2026-08-24T03:55")
                .param("environment", "PROD")
                .param("sourceType", "PULL_REQUEST")
                .param("sourceReference", "1301"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/observability-analysis-action"))
            .andExpect(model().attribute("analysisId", "analysis-trace"))
            .andExpect(model().attribute("signalKey", "log-1787539541000"))
            .andExpect(model().attribute("analysisCompleted", false))
            .andExpect(model().attribute("analysisWorkflowReady", false))
            .andExpect(model().attribute("analysisFailed", false));

        var command = ArgumentCaptor.forClass(
            DashboardUseCase.ObservabilityAnalysisCommand.class
        );
        verify(dashboardUseCase).requestObservabilityAnalysis(command.capture());
        assertThat(command.getValue().observation().startAt().getOffset())
            .isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void reportsFailedObservabilityAnalysisWithoutPromotingAWorkflowCard() throws Exception {
        when(dashboardUseCase.getAnalysis("failed-analysis")).thenReturn(
            new DashboardView.Analysis(
                new DashboardView.AnalysisIdentity("failed-analysis", 2),
                "FAILED",
                List.of(),
                "CANDIDATE_ANALYSIS_FAILED: 후보를 생성하지 못했습니다."
            )
        );

        mockMvc.perform(get("/ui/fragments/analyses/failed-analysis/observability-action")
                .param("signalKey", "log-1787539541000"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/observability-analysis-action"))
            .andExpect(model().attribute("analysisCompleted", true))
            .andExpect(model().attribute("analysisWorkflowReady", false))
            .andExpect(model().attribute("analysisFailed", true))
            .andExpect(model().attribute(
                "analysisFailureReason",
                "CANDIDATE_ANALYSIS_FAILED: 후보를 생성하지 못했습니다."
            ));
    }

    @Test
    void listsRecentFailedPullRequestsWithoutCallingTheLanguageModel() throws Exception {
        when(dashboardUseCase.getFailedPullRequests()).thenReturn(List.of(failedPullRequest()));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "최근 실패한 빌드 PR 리스트 알려줘")
                .param("idempotencyKey", "command-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-failed-pull-requests"))
            .andExpect(model().attribute("pullRequests", List.of(failedPullRequest())));
    }

    @Test
    void proposesTheHighestConfidenceUnstartedCandidateForUrgentWork() throws Exception {
        var workflow = workflowWithCandidate();
        when(dashboardUseCase.getMostUrgentCandidate()).thenReturn(Optional.of(
            new DashboardView.CandidatePriority(
                workflow,
                workflow.candidateWorkflows().getFirst(),
                1,
                "자동 수정 가능한 후보 중 신뢰도가 가장 높습니다."
            )
        ));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "최근 분석한 작업 중 제일 시급한 거 진행해줘")
                .param("idempotencyKey", "urgent-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-workflow-reference"))
            .andExpect(model().attribute("workflow", workflow))
            .andExpect(model().attribute("requestedCandidateNumber", 1))
            .andExpect(model().attributeExists("selectedCandidate"));
    }

    @Test
    void listsCandidatesThatNeedPrecisionAnalysis() throws Exception {
        when(dashboardUseCase.getRefinementPriorities()).thenReturn(List.of());

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "정밀분석 필요한 우선순위 알려줘")
                .param("idempotencyKey", "priority-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-refinement-priorities"))
            .andExpect(model().attribute("priorityCandidates", List.of()));
    }

    @Test
    void startsCandidateRefinementFromAConversationalReference() throws Exception {
        var workflow = workflowWithCandidate();
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(workflow));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "24e37bee 1번 정밀 분석해줘")
                .param("idempotencyKey", "refinement-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-workflow-reference"));

        verify(dashboardUseCase).refineCandidate(any());
    }

    @Test
    void keepsTheTopRefinementPriorityAsConversationContext() throws Exception {
        var workflow = workflowWithCandidate();
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(workflow));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "그럼 1번 정밀 분석해줘")
                .param("conversationContext",
                    "REFINEMENT_PRIORITY|24e37bee-eae1-4dc4-bf7c-c78e9956ba3d|1")
                .param("idempotencyKey", "refinement-follow-up-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-workflow-reference"));

        verify(dashboardUseCase).refineCandidate(any());
    }

    @Test
    void resolvesAnAnalysisPrefixAndRequestedCandidateFromTheConversation()
        throws Exception {
        var workflow = workflowWithCandidate();
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(workflow));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "analysis-는 빼고 24e37bee 이거 1번 진행 가능해?")
                .param("idempotencyKey", "command-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-workflow-reference"))
            .andExpect(model().attribute("workflow", workflow))
            .andExpect(model().attribute("requestedCandidateNumber", 1))
            .andExpect(model().attributeExists("selectedCandidate"));
    }

    @Test
    void explainsAnAnalysisWhenTheConversationOnlyContainsItsIdPrefix()
        throws Exception {
        var workflow = workflowWithCandidate();
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(workflow));

        mockMvc.perform(post("/ui/natural-language/interpretations")
                .param("text", "24e37bee 이거 뭐야")
                .param("idempotencyKey", "command-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/chat-workflow-reference"))
            .andExpect(model().attribute("workflow", workflow))
            .andExpect(model().attribute("displayCandidates", workflow.candidateWorkflows()))
            .andExpect(model().attributeDoesNotExist("selectedCandidate"));
    }

    @Test
    void startsDraftWorkflowOnlyAfterCandidateSelection() throws Exception {
        var resource = new DashboardView.ResourceResult(
            "hotfix-1",
            "SELECTED",
            "/api/v1/hotfixes/hotfix-1"
        );
        when(dashboardUseCase.selectCandidate(any())).thenReturn(resource);

        mockMvc.perform(post("/ui/analyses/analysis-1/selections")
                .param("analysisVersion", "1")
                .param("candidateId", "candidate-1")
                .param("idempotencyKey", "selection-key"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/selection"))
            .andExpect(model().attribute("resource", resource));

        verify(dashboardUseCase).selectCandidate(any());
    }

    @Test
    void refreshesOnlyTheSelectedWorkflowCard() throws Exception {
        var resource = new DashboardView.ResourceResult(
            "hotfix-1",
            "SELECTED",
            "/api/v1/hotfixes/hotfix-1"
        );
        var workflow = completedWorkflow();
        when(dashboardUseCase.selectCandidate(any())).thenReturn(resource);
        when(dashboardUseCase.getWorkflowItems()).thenReturn(List.of(workflow));

        mockMvc.perform(post("/ui/analyses/analysis-1/selections")
                .param("analysisVersion", "1")
                .param("candidateId", "candidate-1")
                .param("idempotencyKey", "selection-key")
                .param("cardOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard/fragments/workflows :: card"))
            .andExpect(model().attribute("workflows", List.of(workflow)));
    }

    private DashboardView.FailedPullRequest failedPullRequest() {
        return new DashboardView.FailedPullRequest(
            new DashboardView.PullRequestReference(1292, "https://bitbucket/pr/1292"),
            new DashboardView.BranchReference("feature/failure", "abcdef0123456789"),
            new DashboardView.BuildReference(
                "FMS-EU/job/PR-1292",
                1,
                "FAILURE",
                Instant.parse("2026-08-24T00:00:00Z"),
                "https://jenkins/job/PR-1292/1/"
            )
        );
    }

    private DashboardView.InterpretationPreview confirmablePreview() {
        return new DashboardView.InterpretationPreview(
            new DashboardView.Metadata(
                "interpretation-1",
                1,
                Instant.parse("2026-08-24T01:00:00Z")
            ),
            new DashboardView.Decision(
                "READY_FOR_CONFIRMATION",
                "ANALYZE_JENKINS",
                "PR-1301 build 1",
                List.of(),
                null,
                "command-hash"
            )
        );
    }

    private DashboardView.Analysis analysis(String status) {
        return new DashboardView.Analysis(
            new DashboardView.AnalysisIdentity("analysis-1", 1),
            status,
            List.of(),
            null
        );
    }

    private DashboardView.WorkflowItem completedWorkflow() {
        return new DashboardView.WorkflowItem(
            new DashboardView.StoredAnalysis(
                analysis("CANDIDATES_READY"),
                new DashboardView.AnalysisSource(
                    "PULL_REQUEST",
                    "PR-1292",
                    "feature/test-failure-test",
                    "abcdef0123456789"
                ),
                Instant.parse("2026-08-24T00:01:00Z")
            ),
            List.of()
        );
    }

    private DashboardView.WorkflowItem workflowWithCandidate() {
        return workflowWithCandidate("PR-1301", "feature/compile-failure-test");
    }

    private DashboardView.WorkflowItem workflowWithCandidate(
        String reference,
        String branch
    ) {
        var candidate = new DashboardView.Candidate(
            "candidate-1",
            "Missing class",
            "Referenced class is missing.",
            0.9,
            "ELIGIBLE",
            null
        );
        return new DashboardView.WorkflowItem(
            new DashboardView.StoredAnalysis(
                new DashboardView.Analysis(
                    new DashboardView.AnalysisIdentity(
                        "24e37bee-eae1-4dc4-bf7c-c78e9956ba3d",
                        1
                    ),
                    "CANDIDATES_READY",
                    List.of(candidate),
                    null
                ),
                new DashboardView.AnalysisSource(
                    "PULL_REQUEST",
                    reference,
                    branch,
                    "abcdef0123456789"
                ),
                Instant.parse("2026-08-24T00:01:00Z")
            ),
            List.of(new DashboardView.CandidateWorkflow(candidate, null))
        );
    }
}
