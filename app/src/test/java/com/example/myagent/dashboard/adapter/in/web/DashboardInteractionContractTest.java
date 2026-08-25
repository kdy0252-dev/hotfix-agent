package com.example.myagent.dashboard.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DashboardInteractionContractTest {
    @Test
    void keepsExistingWorkflowCardsWhileOneAnalysisRunsAndPromotesOnlyItsResult()
        throws Exception {
        String script = resource("static/js/dashboard.js");
        String pullRequests = resource("templates/dashboard/fragments/pull-requests.html");
        String workflows = resource("templates/dashboard/fragments/workflows.html");

        assertThat(script)
            .doesNotContain("#workflow-list\")?.replaceChildren")
            .contains(
                "refreshWorkflowCard(analysisId)",
                "refreshWorkflowCard(analysisId, attempt + 1)",
                "promoteCompletedAnalyses(document)",
                "htmx:afterSettle",
                "workflowList.prepend(importedCard)"
            );
        assertThat(pullRequests)
            .contains("hx-target=|#analysis-action-${item.pullRequest.number}|")
            .doesNotContain("hx-target=\"#workflow-list\"");
        assertThat(workflows)
            .contains("th:fragment=\"card\"", "/ui/fragments/workflows/")
            .doesNotContain("hx-trigger=\"every 2s\"");
    }

    @Test
    void rendersProgressInsideTheRequestButtonAndAnExplicitDuplicateAction()
        throws Exception {
        String action = resource("templates/dashboard/fragments/analysis-action.html");
        String styles = resource("static/css/dashboard.css");

        assertThat(action)
            .contains("AI 분석 진행 중…", "중복 요청 재분석", "analysisStatusUrl");
        assertThat(styles)
            .contains("analysis-progress", ".analysis-request-button.analysis-running");
    }

    @Test
    void replacesThePullRequestListContainerWithoutNestingItOnRefresh() throws Exception {
        String dashboard = resource("templates/dashboard/index.html");

        assertThat(dashboard)
            .contains("hx-target=\"#pull-request-list\" hx-swap=\"outerHTML\">새로고침");
    }

    @Test
    void usesTheHotfixFavicon() throws Exception {
        String dashboard = resource("templates/dashboard/index.html");
        String favicon = resource("static/favicon.svg");

        assertThat(dashboard).contains("rel=\"icon\" type=\"image/svg+xml\"");
        assertThat(favicon).contains("<svg", "aria-label=\"FMS Hotfix\"");
    }

    @Test
    void rendersLiveStageProgressWithoutTheDuplicatedSummaryBelowTheCards()
        throws Exception {
        String workflows = resource("templates/dashboard/fragments/workflows.html");
        String styles = resource("static/css/dashboard.css");

        assertThat(workflows)
            .contains(
                "stage-progress-track",
                "stageState.elapsedLabel",
                "pipeline-flow",
                "pipelineStage.status",
                "Jenkins Pipeline 단계"
            )
            .doesNotContain("actorTrail", "통과 경로")
            .doesNotContain("class=\"progress-message\"");
        assertThat(styles).contains(
            "@keyframes stage-progress",
            ".stage-runtime-head",
            ".pipeline-node.IN_PROGRESS",
            ".pipeline-node.FAILED"
        );
    }

    @Test
    void replacesOnlyTheWorkflowCardAfterCandidateSelection() throws Exception {
        String workflows = resource("templates/dashboard/fragments/workflows.html");

        assertThat(workflows)
            .contains(
                "hx-target=\"closest .workflow-card\"",
                "name=\"cardOnly\" value=\"true\""
            );
    }

    @Test
    void exposesServerRestartRecoveryOnTheActiveCard() throws Exception {
        String workflows = resource("templates/dashboard/fragments/workflows.html");
        String styles = resource("static/css/dashboard.css");

        assertThat(workflows).contains("recovery-state", "RESTART RECOVERY", "서버 재기동");
        assertThat(styles).contains(".recovery-state");
    }

    @Test
    void locksNaturalLanguageEditingAndConfirmsDuplicateReanalysis() throws Exception {
        String dashboard = resource("templates/dashboard/index.html");
        String script = resource("static/js/dashboard.js");
        String styles = resource("static/css/dashboard.css");

        assertThat(dashboard).contains("data-command-form", "command-progress");
        assertThat(script).contains(
            "중복 요청 다시 해석",
            "window.crypto.randomUUID()",
            "setAttribute(\"disabled\"",
            "htmx:beforeRequest",
            "htmx:afterRequest"
        );
        assertThat(dashboard).contains("자연어 분석기가 해석 중입니다", "command-progress-bar");
        assertThat(styles).contains(
            ".command-form.interpreting",
            ".command-progress-bar i",
            "@keyframes command-progress"
        );
    }

    @Test
    void opensDetailsForSignalsAndCandidatesWithoutExpandingEveryCard() throws Exception {
        String observability = resource("templates/dashboard/fragments/observability.html");
        String workflows = resource("templates/dashboard/fragments/workflows.html");
        String script = resource("static/js/dashboard.js");

        assertThat(observability).contains(
            "data-modal-open",
            "detail-modal",
            "signal.reference.linkLabel",
            "name=\"sourceReference\" value=\"main\"",
            "name=\"signalKey\"",
            "signal.analysisStartAt",
            "AI 분석"
        );
        assertThat(workflows).contains("candidate-summary", "ANALYSIS CANDIDATE", "Details");
        assertThat(script).contains("showModal()", "data-modal-close");
    }

    @Test
    void usesAFloatingConversationalAssistantWithAffirmativeDraftConfirmation()
        throws Exception {
        String dashboard = resource("templates/dashboard/index.html");
        String chatAnalysis = resource("templates/dashboard/fragments/chat-analysis.html");
        String script = resource("static/js/dashboard.js");
        String styles = resource("static/css/dashboard.css");

        assertThat(dashboard)
            .contains("chat-launcher", "chat-drawer", "AI 상담", "Enter 전송")
            .doesNotContain("<section class=\"hero panel\"");
        assertThat(chatAnalysis).contains(
            "챗봇 요청을 분석 중입니다",
            "요청이 완료되었습니다. PR 분석 결과입니다",
            "Draft PR 생성을 진행할까요",
            "data-chat-confirm-form"
        );
        assertThat(script).contains(
            "isAffirmative",
            "confirmationForms.length === 1",
            "refreshWorkflowList",
            "showModal()"
        );
        assertThat(styles).contains(".chat-launcher", ".chat-drawer.open", ".chat-message.user");
    }

    @Test
    void showsObservabilityProgressInsideTheButtonAndAlwaysAnalyzesMain() throws Exception {
        String dashboard = resource("templates/dashboard/index.html");
        String observability = resource("templates/dashboard/fragments/observability.html");
        String styles = resource("static/css/dashboard.css");

        assertThat(dashboard).contains(
            "observability-query-button",
            "Grafana 조회 중",
            "hx-indicator=\"this\"",
            "시작 (KST)",
            "종료 (KST)"
        );
        assertThat(observability)
            .contains(
                "name=\"sourceType\" value=\"BRANCH\"",
                "name=\"sourceReference\" value=\"main\"",
                "occurredAtKstLabel",
                "observability-analysis-form",
                "hx-target=|#observability-analysis-${signal.analysisKey}|"
            )
            .doesNotContain(
                "<select name=\"sourceType\"",
                "placeholder=\"예: main 또는 1301\""
            );
        assertThat(styles).contains(
            ".observability-query-form.htmx-request",
            ".query-spinner",
            ".signal-content {",
            ".signal-entry > .observability-analysis-action",
            "text-align: left"
        );
    }

    @Test
    void paginatesSignalAndPullRequestCardsAndFiltersWorkflowsByPullRequest()
        throws Exception {
        String observability = resource("templates/dashboard/fragments/observability.html");
        String pullRequests = resource("templates/dashboard/fragments/pull-requests.html");
        String workflows = resource("templates/dashboard/fragments/workflows.html");
        String script = resource("static/js/dashboard.js");
        String styles = resource("static/css/dashboard.css");

        assertThat(observability).contains("data-paginated-list", "data-page-size");
        assertThat(pullRequests).contains("data-paginated-list", "data-page-item");
        assertThat(workflows).contains("data-workflow-filter", "data-workflow-reference");
        assertThat(script).contains(
            "configurePagination",
            "selectedWorkflowReference",
            "card.dataset.workflowReference"
        );
        assertThat(styles).contains(
            "[data-page-item][hidden]",
            ".workflow-card[hidden]",
            "display: none !important"
        );
    }

    @Test
    void rendersDatabaseBackedChatListsAndAnalysisReferenceConfirmation()
        throws Exception {
        String failedList = resource(
            "templates/dashboard/fragments/chat-failed-pull-requests.html"
        );
        String reference = resource(
            "templates/dashboard/fragments/chat-workflow-reference.html"
        );

        assertThat(failedList).contains(
            "최근 실패한 PR 목록입니다",
            "Bitbucket ↗",
            "Jenkins ↗"
        );
        assertThat(reference).contains(
            "작업 ID",
            "진행 가능",
            "data-chat-confirm-form",
            "“응”이라고 입력"
        );
    }

    @Test
    void replacesNativeHotfixCancellationAlertsWithTheApplicationModal()
        throws Exception {
        String dashboard = resource("templates/dashboard/index.html");
        String workflows = resource("templates/dashboard/fragments/workflows.html");
        String script = resource("static/js/dashboard.js");

        assertThat(dashboard).contains(
            "action-confirmation-modal",
            "작업 취소 및 삭제",
            "data-confirmation-accept"
        );
        assertThat(workflows).contains("hx-confirm=");
        assertThat(script).contains(
            "htmx:confirm",
            "event.preventDefault()",
            "event.detail.issueRequest(true)"
        );
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
