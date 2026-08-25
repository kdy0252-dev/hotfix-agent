package com.example.myagent.dashboard.adapter.in.web;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.dashboard.application.port.in.DashboardUseCase;
import com.example.myagent.dashboard.application.port.in.DashboardUseCaseException;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import io.vavr.control.Try;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

@Adapter
@Controller
@Validated
public class DashboardController {
    private static final Pattern ANALYSIS_REFERENCE = Pattern.compile(
        "(?i)\\b([0-9a-f]{8})\\b"
    );
    private static final Pattern CANDIDATE_NUMBER = Pattern.compile("(\\d+)번");
    private static final ZoneId OBSERVABILITY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final DashboardUseCase dashboardUseCase;
    private final ObservabilityScopeProperties observabilityScope;

    public DashboardController(
        DashboardUseCase dashboardUseCase,
        ObservabilityScopeProperties observabilityScope
    ) {
        this.dashboardUseCase = dashboardUseCase;
        this.observabilityScope = observabilityScope;
    }

    @GetMapping("/")
    public String index(Model model) {
        LocalDateTime endAt = LocalDateTime.now(OBSERVABILITY_ZONE)
            .withSecond(0)
            .withNano(0);
        model.addAttribute("startAt", endAt.minusMinutes(30).format(DATE_TIME_FORMATTER));
        model.addAttribute("endAt", endAt.format(DATE_TIME_FORMATTER));
        model.addAttribute("commandIdempotencyKey", UUID.randomUUID().toString());
        model.addAttribute("observabilityServiceName", observabilityScope.displayName());
        return "dashboard/index";
    }

    @GetMapping("/ui")
    public String redirectLegacyIndex() {
        return "redirect:/";
    }

    @GetMapping("/ui/fragments/pull-requests")
    public String failedPullRequests(Model model) {
        var pullRequests = dashboardUseCase.getFailedPullRequests();
        var workflows = dashboardUseCase.getWorkflowItems();
        model.addAttribute("pullRequests", pullRequests);
        model.addAttribute(
            "duplicatePullRequestIds",
            duplicatePullRequestIds(pullRequests, workflows)
        );
        return "dashboard/fragments/pull-requests";
    }

    @PostMapping("/ui/analyses/jenkins")
    public String requestJenkinsAnalysis(
        @RequestParam @NotBlank String jobPath,
        @RequestParam @Positive long buildNumber,
        @RequestParam @Positive long pullRequestId,
        @RequestParam @NotBlank String idempotencyKey,
        @RequestParam(defaultValue = "false") boolean force,
        Model model
    ) {
        String effectiveIdempotencyKey = force
            ? idempotencyKey + "-force-" + UUID.randomUUID() : idempotencyKey;
        var execution = dashboardUseCase.requestJenkinsAnalysis(
            new DashboardUseCase.JenkinsAnalysisCommand(
                jobPath,
                buildNumber,
                pullRequestId,
                effectiveIdempotencyKey
            )
        );
        boolean duplicateRequest = !force && !"ANALYSIS_REQUESTED".equals(execution.status());
        boolean analysisCompleted = dashboardUseCase.getAnalysis(execution.resourceId()).completed();
        return analysisActionFragment(model, new AnalysisActionState(
            execution.resourceId(),
            new JenkinsAnalysisRequest(jobPath, buildNumber, pullRequestId, idempotencyKey),
            duplicateRequest,
            analysisCompleted
        ));
    }

    @GetMapping("/ui/fragments/workflows")
    public String workflows(
        @RequestParam(required = false) String branch,
        Model model
    ) {
        return workflowFragment(branch, model);
    }

    @GetMapping("/ui/fragments/workflows/{analysisId}")
    public String workflow(
        @PathVariable @NotBlank String analysisId,
        Model model
    ) {
        var workflows = dashboardUseCase.getWorkflowItems().stream()
            .filter(item -> item.storedAnalysis().analysis().identity().analysisId()
                .equals(analysisId))
            .toList();
        model.addAttribute("workflows", workflows);
        return "dashboard/fragments/workflows :: card";
    }

    @GetMapping("/ui/fragments/analyses/{analysisId}/action")
    public String analysisAction(
        @PathVariable @NotBlank String analysisId,
        @RequestParam @NotBlank String jobPath,
        @RequestParam @Positive long buildNumber,
        @RequestParam @Positive long pullRequestId,
        @RequestParam @NotBlank String idempotencyKey,
        Model model
    ) {
        boolean completed = dashboardUseCase.getAnalysis(analysisId).completed();
        return analysisActionFragment(model, new AnalysisActionState(
            analysisId,
            new JenkinsAnalysisRequest(jobPath, buildNumber, pullRequestId, idempotencyKey),
            false,
            completed
        ));
    }

    @PostMapping("/ui/hotfixes/{hotfixId}/restarts")
    public String restartHotfix(
        @PathVariable @NotBlank String hotfixId,
        Model model
    ) {
        dashboardUseCase.restartHotfix(new DashboardUseCase.RestartCommand(
            hotfixId,
            "dashboard-restart-" + hotfixId + "-" + UUID.randomUUID()
        ));
        return workflowFragment(model);
    }

    @PostMapping("/ui/hotfixes/{hotfixId}/human-review-branch")
    public String publishHumanReviewBranch(
        @PathVariable @NotBlank String hotfixId,
        Model model
    ) {
        dashboardUseCase.publishHumanReviewBranch(hotfixId);
        return workflowFragment(model);
    }

    @PostMapping("/ui/hotfixes/{hotfixId}/human-changes-verification")
    public String verifyHumanChanges(
        @PathVariable @NotBlank String hotfixId,
        Model model
    ) {
        dashboardUseCase.verifyHumanChanges(hotfixId);
        return workflowFragment(model);
    }

    @PostMapping("/ui/hotfixes/{hotfixId}/ci-refresh")
    public String refreshHotfixCi(
        @PathVariable @NotBlank String hotfixId,
        Model model
    ) {
        dashboardUseCase.refreshHotfixCi(hotfixId);
        return workflowFragment(model);
    }

    @DeleteMapping("/ui/hotfixes/{hotfixId}")
    public String cancelAndDeleteHotfix(
        @PathVariable @NotBlank String hotfixId,
        Model model
    ) {
        dashboardUseCase.cancelAndDeleteHotfix(hotfixId);
        return workflowFragment(model);
    }

    @DeleteMapping("/ui/workflows/{analysisId}")
    public String cancelAndDeleteWorkflow(
        @PathVariable @NotBlank String analysisId,
        Model model
    ) {
        dashboardUseCase.cancelAndDeleteWorkflow(analysisId);
        return workflowFragment(model);
    }

    @GetMapping("/ui/fragments/observability")
    public String observability(
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startAt,
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime endAt,
        @RequestParam @NotBlank String environment,
        Model model
    ) {
        model.addAttribute("signals", dashboardUseCase.getObservabilitySignals(
            new DashboardUseCase.ObservabilityCommand(
                startAt.atZone(OBSERVABILITY_ZONE).toOffsetDateTime(),
                endAt.atZone(OBSERVABILITY_ZONE).toOffsetDateTime(),
                environment
            )
        ));
        model.addAttribute("environment", environment);
        model.addAttribute("observabilityServiceName", observabilityScope.displayName());
        model.addAttribute("startAt", startAt.format(DATE_TIME_FORMATTER));
        model.addAttribute("endAt", endAt.format(DATE_TIME_FORMATTER));
        return "dashboard/fragments/observability";
    }

    @PostMapping("/ui/analyses/observability")
    public String requestObservabilityAnalysis(
        @RequestParam @NotBlank String signalKey,
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startAt,
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime endAt,
        @RequestParam @NotBlank String environment,
        @RequestParam @NotBlank String sourceType,
        @RequestParam @NotBlank String sourceReference,
        Model model
    ) {
        var execution = dashboardUseCase.requestObservabilityAnalysis(
            new DashboardUseCase.ObservabilityAnalysisCommand(
                new DashboardUseCase.ObservabilityCommand(
                    startAt.atZone(OBSERVABILITY_ZONE).toOffsetDateTime(),
                    endAt.atZone(OBSERVABILITY_ZONE).toOffsetDateTime(),
                    environment
                ),
                source(sourceType, sourceReference),
                "dashboard-observability-" + signalKey + '-' + UUID.randomUUID()
            )
        );
        var analysis = dashboardUseCase.getAnalysis(execution.resourceId());
        return observabilityAnalysisActionFragment(
            model,
            new ObservabilityAnalysisState(
                execution.resourceId(),
                signalKey,
                analysis.status(),
                analysis.failureReason()
            )
        );
    }

    @GetMapping("/ui/fragments/analyses/{analysisId}/observability-action")
    public String observabilityAnalysisAction(
        @PathVariable @NotBlank String analysisId,
        @RequestParam @NotBlank String signalKey,
        Model model
    ) {
        var analysis = dashboardUseCase.getAnalysis(analysisId);
        return observabilityAnalysisActionFragment(model, new ObservabilityAnalysisState(
            analysisId,
            signalKey,
            analysis.status(),
            analysis.failureReason()
        ));
    }

    private String workflowFragment(Model model) {
        return workflowFragment(null, model);
    }

    private String workflowFragment(String branch, Model model) {
        var allWorkflows = dashboardUseCase.getWorkflowItems();
        var workflows = branch == null || branch.isBlank()
            ? allWorkflows
            : allWorkflows.stream()
                .filter(item -> branch.equals(item.storedAnalysis().source().branch()))
                .toList();
        model.addAttribute("workflows", workflows);
        model.addAttribute("workflowBranches", allWorkflows.stream()
            .map(item -> item.storedAnalysis().source().branch())
            .distinct()
            .sorted()
            .toList());
        model.addAttribute("selectedWorkflowBranch", branch == null ? "" : branch);
        return "dashboard/fragments/workflows";
    }

    private String observabilityAnalysisActionFragment(
        Model model,
        ObservabilityAnalysisState state
    ) {
        String statusUrl = UriComponentsBuilder
            .fromPath("/ui/fragments/analyses/{analysisId}/observability-action")
            .queryParam("signalKey", state.signalKey())
            .buildAndExpand(state.analysisId())
            .encode()
            .toUriString();
        model.addAttribute("analysisId", state.analysisId());
        model.addAttribute("signalKey", state.signalKey());
        model.addAttribute("analysisCompleted", state.terminal());
        model.addAttribute("analysisWorkflowReady", state.workflowReady());
        model.addAttribute("analysisFailed", state.failed());
        model.addAttribute("analysisFailureReason", state.failureReason());
        model.addAttribute("analysisStatusUrl", statusUrl);
        return "dashboard/fragments/observability-analysis-action";
    }

    private DashboardUseCase.SourceCommand source(String type, String reference) {
        return switch (type) {
            case "BRANCH" -> new DashboardUseCase.SourceCommand("BRANCH", reference, null);
            case "PULL_REQUEST" -> new DashboardUseCase.SourceCommand(
                "PULL_REQUEST",
                null,
                Try.of(() -> Long.valueOf(reference)).getOrElseThrow(() ->
                    new DashboardUseCaseException(
                        "INVALID_PULL_REQUEST_SOURCE",
                        "PR source에는 숫자 PR 번호가 필요합니다."
                    )
                )
            );
            default -> throw new DashboardUseCaseException(
                "INVALID_SOURCE_TYPE",
                "source type은 BRANCH 또는 PULL_REQUEST여야 합니다."
            );
        };
    }

    private String analysisActionFragment(
        Model model,
        AnalysisActionState state
    ) {
        JenkinsAnalysisRequest request = state.request();
        String statusUrl = UriComponentsBuilder
            .fromPath("/ui/fragments/analyses/{analysisId}/action")
            .queryParam("jobPath", request.jobPath())
            .queryParam("buildNumber", request.buildNumber())
            .queryParam("pullRequestId", request.pullRequestId())
            .queryParam("idempotencyKey", request.idempotencyKey())
            .buildAndExpand(state.analysisId())
            .encode()
            .toUriString();
        model.addAttribute("analysisId", state.analysisId());
        model.addAttribute("analysisStatusUrl", statusUrl);
        model.addAttribute("analysisCompleted", state.completed());
        model.addAttribute("duplicateRequest", state.duplicateRequest());
        model.addAttribute("jenkinsJobPath", request.jobPath());
        model.addAttribute("jenkinsBuildNumber", request.buildNumber());
        model.addAttribute("jenkinsPullRequestId", request.pullRequestId());
        model.addAttribute("jenkinsIdempotencyKey", request.idempotencyKey());
        return "dashboard/fragments/analysis-action";
    }

    private Set<Long> duplicatePullRequestIds(
        List<DashboardView.FailedPullRequest> pullRequests,
        List<DashboardView.WorkflowItem> workflows
    ) {
        Set<String> completedSources = Set.copyOf(workflows.stream()
            .filter(workflow -> workflow.storedAnalysis().analysis().completed())
            .map(workflow -> sourceKey(
                workflow.storedAnalysis().source().reference(),
                workflow.storedAnalysis().source().commit()
            ))
            .toList());
        return Set.copyOf(pullRequests.stream()
            .filter(pullRequest -> completedSources.contains(sourceKey(
                "PR-" + pullRequest.pullRequest().number(),
                pullRequest.branch().commit()
            )))
            .map(pullRequest -> pullRequest.pullRequest().number())
            .toList());
    }

    private String sourceKey(String reference, String commit) {
        return reference + '@' + commit;
    }

    private record JenkinsAnalysisRequest(
        String jobPath,
        long buildNumber,
        long pullRequestId,
        String idempotencyKey
    ) {
    }

    private record AnalysisActionState(
        String analysisId,
        JenkinsAnalysisRequest request,
        boolean duplicateRequest,
        boolean completed
    ) {
    }

    private record ObservabilityAnalysisState(
        String analysisId,
        String signalKey,
        String status,
        String failureReason
    ) {
        private boolean terminal() {
            return workflowReady() || failed();
        }

        private boolean workflowReady() {
            return "CANDIDATES_READY".equals(status)
                || "NEEDS_HUMAN_REVIEW".equals(status);
        }

        private boolean failed() {
            return "FAILED".equals(status);
        }
    }

    @GetMapping("/ui/fragments/hotfixes")
    public String hotfixes(Model model) {
        model.addAttribute("hotfixes", dashboardUseCase.getHotfixProgresses());
        return "dashboard/fragments/hotfixes";
    }

    @GetMapping("/ui/fragments/command")
    public String commandForm(
        @RequestParam(defaultValue = "") String text,
        Model model
    ) {
        model.addAttribute("commandText", text);
        model.addAttribute("commandIdempotencyKey", UUID.randomUUID().toString());
        return "dashboard/fragments/command-form";
    }

    @PostMapping("/ui/natural-language/interpretations")
    public String interpret(
        @RequestParam @NotBlank @Size(max = 2_000) String text,
        @RequestParam @NotBlank String idempotencyKey,
        @RequestParam(defaultValue = "") String conversationContext,
        Model model
    ) {
        if (requestsFailedPullRequestList(text)) {
            model.addAttribute("pullRequests", dashboardUseCase.getFailedPullRequests());
            return "dashboard/fragments/chat-failed-pull-requests";
        }
        if (requestsRefinementPriorities(text)) {
            model.addAttribute("priorityCandidates", dashboardUseCase.getRefinementPriorities());
            return "dashboard/fragments/chat-refinement-priorities";
        }
        if (requestsMostUrgentWork(text)) {
            return mostUrgentWork(model);
        }
        String contextualizedText = contextualizedText(text, conversationContext);
        Optional<DashboardView.WorkflowItem> referenced = referencedWorkflow(contextualizedText);
        if (referenced.isPresent()) {
            Integer requestedCandidateNumber = candidateNumber(contextualizedText);
            if (requestsCandidateRefinement(contextualizedText)
                && requestedCandidateNumber != null) {
                refineReferencedCandidate(referenced.get(), requestedCandidateNumber);
            }
            return workflowReferenceFragment(
                model,
                referenced.get(),
                requestedCandidateNumber
            );
        }
        var interpretation = dashboardUseCase.interpretNaturalLanguage(
            new DashboardUseCase.InterpretationCommand(contextualizedText, idempotencyKey)
        );
        if (interpretation.decision().confirmable()
            && "ANALYZE_JENKINS".equals(interpretation.decision().intent())) {
            var execution = dashboardUseCase.executeNaturalLanguage(
                new DashboardUseCase.ExecutionCommand(
                interpretation.metadata().interpretationId(),
                interpretation.metadata().version(),
                interpretation.decision().commandHash(),
                UUID.randomUUID().toString()
                )
            );
            return chatAnalysisFragment(model, execution.resourceId());
        }
        model.addAttribute("interpretation", interpretation);
        model.addAttribute("executionIdempotencyKey", UUID.randomUUID().toString());
        return "dashboard/fragments/interpretation";
    }

    private String contextualizedText(String text, String conversationContext) {
        if ("LATEST_FAILED_PULL_REQUEST".equals(conversationContext)
            && referencesPriorMessage(text)) {
            return "최근 실패 빌드 " + text;
        }
        if (conversationContext.startsWith("REFINEMENT_PRIORITY|")
            && referencesPriority(text)) {
            String[] contextParts = conversationContext.split("\\|", 3);
            if (contextParts.length == 3) {
                return contextParts[1] + " " + contextParts[2] + "번 " + text;
            }
        }
        return text;
    }

    private boolean referencesPriority(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("1번")
            || normalized.contains("첫 번째")
            || normalized.contains("첫번째")
            || referencesPriorMessage(text);
    }

    private boolean referencesPriorMessage(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("그거")
            || normalized.contains("그것")
            || normalized.contains("저거")
            || normalized.contains("이거")
            || normalized.contains("해당 건")
            || normalized.contains("that");
    }

    @GetMapping("/ui/fragments/natural-language/interpretations/{interpretationId}")
    public String interpretation(
        @PathVariable @NotBlank String interpretationId,
        Model model
    ) {
        model.addAttribute(
            "interpretation",
            dashboardUseCase.getNaturalLanguageInterpretation(interpretationId)
        );
        model.addAttribute("executionIdempotencyKey", UUID.randomUUID().toString());
        return "dashboard/fragments/interpretation";
    }

    private boolean requestsFailedPullRequestList(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean asksRecentFailures = normalized.contains("최근")
            && normalized.contains("실패")
            && (normalized.contains("pr")
                || normalized.contains("빌드")
                || normalized.contains("build"));
        return asksRecentFailures
            && (normalized.contains("리스트")
                || normalized.contains("목록")
                || normalized.contains("알려")
                || normalized.contains("보여"));
    }

    private boolean requestsRefinementPriorities(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("정밀")
            && (normalized.contains("우선")
                || normalized.contains("필요")
                || normalized.contains("순위"));
    }

    private boolean requestsMostUrgentWork(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return (normalized.contains("시급") || normalized.contains("가장 우선"))
            && (normalized.contains("진행") || normalized.contains("처리"));
    }

    private boolean requestsCandidateRefinement(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("정밀") && normalized.contains("분석");
    }

    private void refineReferencedCandidate(
        DashboardView.WorkflowItem workflow,
        int candidateNumber
    ) {
        if (candidateNumber < 1 || candidateNumber > workflow.candidateWorkflows().size()) {
            return;
        }
        var analysis = workflow.storedAnalysis().analysis();
        var candidate = workflow.candidateWorkflows().get(candidateNumber - 1).candidate();
        dashboardUseCase.refineCandidate(new DashboardUseCase.RefinementCommand(
            analysis.identity().analysisId(),
            analysis.identity().version(),
            candidate.candidateId()
        ));
    }

    private String mostUrgentWork(Model model) {
        return dashboardUseCase.getMostUrgentCandidate()
            .map(priority -> workflowReferenceFragment(
                model,
                priority.workflow(),
                priority.candidateNumber()
            ))
            .orElseGet(() -> {
                model.addAttribute("priorityCandidates", List.of());
                return "dashboard/fragments/chat-refinement-priorities";
            });
    }

    private Optional<DashboardView.WorkflowItem> referencedWorkflow(String text) {
        var matcher = ANALYSIS_REFERENCE.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String prefix = matcher.group(1).toLowerCase(Locale.ROOT);
        return dashboardUseCase.getWorkflowItems().stream()
            .filter(item -> item.storedAnalysis().analysis().identity().analysisId()
                .toLowerCase(Locale.ROOT)
                .startsWith(prefix))
            .findFirst();
    }

    private Integer candidateNumber(String text) {
        var matcher = CANDIDATE_NUMBER.matcher(text);
        return matcher.find()
            ? Try.of(() -> Integer.valueOf(matcher.group(1))).getOrNull()
            : null;
    }

    private String workflowReferenceFragment(
        Model model,
        DashboardView.WorkflowItem workflow,
        Integer requestedCandidateNumber
    ) {
        var candidates = workflow.candidateWorkflows();
        DashboardView.CandidateWorkflow selectedCandidate = requestedCandidateNumber != null
            && requestedCandidateNumber > 0
            && requestedCandidateNumber <= candidates.size()
            ? candidates.get(requestedCandidateNumber - 1)
            : null;
        model.addAttribute("workflow", workflow);
        model.addAttribute("requestedCandidateNumber", requestedCandidateNumber);
        model.addAttribute("selectedCandidate", selectedCandidate);
        model.addAttribute(
            "displayCandidates",
            selectedCandidate == null ? candidates : List.of(selectedCandidate)
        );
        model.addAttribute("selectionIdempotencyKey", UUID.randomUUID().toString());
        return "dashboard/fragments/chat-workflow-reference";
    }

    @PostMapping("/ui/natural-language/interpretations/{interpretationId}/executions")
    public String execute(
        @PathVariable @NotBlank String interpretationId,
        @RequestParam @Positive long version,
        @RequestParam @NotBlank String commandHash,
        @RequestParam @NotBlank String idempotencyKey,
        Model model
    ) {
        model.addAttribute("execution", dashboardUseCase.executeNaturalLanguage(
            new DashboardUseCase.ExecutionCommand(
                interpretationId,
                version,
                commandHash,
                idempotencyKey
            )
        ));
        model.addAttribute("storedAnalysis", false);
        return "dashboard/fragments/execution";
    }

    @GetMapping("/ui/fragments/analyses/{analysisId}")
    public String analysis(
        @PathVariable @NotBlank String analysisId,
        Model model
    ) {
        model.addAttribute("analysis", dashboardUseCase.getAnalysis(analysisId));
        model.addAttribute("selectionIdempotencyKey", UUID.randomUUID().toString());
        return "dashboard/fragments/analysis";
    }

    @GetMapping("/ui/fragments/chat/analyses/{analysisId}")
    public String chatAnalysis(
        @PathVariable @NotBlank String analysisId,
        Model model
    ) {
        return chatAnalysisFragment(model, analysisId);
    }

    private String chatAnalysisFragment(Model model, String analysisId) {
        model.addAttribute("analysis", dashboardUseCase.getAnalysis(analysisId));
        model.addAttribute("selectionIdempotencyKey", UUID.randomUUID().toString());
        return "dashboard/fragments/chat-analysis";
    }

    @PostMapping("/ui/analyses/{analysisId}/selections")
    public String selectCandidate(
        @PathVariable @NotBlank String analysisId,
        @RequestParam @Positive long analysisVersion,
        @RequestParam @NotBlank String candidateId,
        @RequestParam @NotBlank String idempotencyKey,
        @RequestParam(defaultValue = "false") boolean cardOnly,
        @RequestParam(defaultValue = "false") boolean chatMode,
        Model model
    ) {
        model.addAttribute("resource", dashboardUseCase.selectCandidate(
            new DashboardUseCase.SelectionCommand(
                analysisId,
                analysisVersion,
                candidateId,
                idempotencyKey
            )
        ));
        if (cardOnly) {
            return workflow(analysisId, model);
        }
        return chatMode
            ? "dashboard/fragments/chat-selection"
            : "dashboard/fragments/selection";
    }

    @PostMapping("/ui/analyses/{analysisId}/candidates/{candidateId}/refinement")
    public String refineCandidate(
        @PathVariable @NotBlank String analysisId,
        @PathVariable @NotBlank String candidateId,
        @RequestParam @Positive long analysisVersion,
        Model model
    ) {
        dashboardUseCase.refineCandidate(new DashboardUseCase.RefinementCommand(
            analysisId,
            analysisVersion,
            candidateId
        ));
        return workflow(analysisId, model);
    }
}
