package com.example.myagent.dashboard.application.domain.service.internal;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.global.annotation.InternalService;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

@InternalService
public class ConversationPriorityResolver {
    public Optional<DashboardView.CandidatePriority> mostUrgent(
        List<DashboardView.WorkflowItem> workflows
    ) {
        return priorities(workflows).stream()
            .filter(priority -> priority.candidateWorkflow().candidate().selectable())
            .filter(priority -> priority.candidateWorkflow().hotfix() == null)
            .sorted(Comparator
                .comparingDouble((DashboardView.CandidatePriority priority) ->
                    priority.candidateWorkflow().candidate().confidence())
                .reversed()
                .thenComparing(priority -> priority.workflow().storedAnalysis().createdAt(),
                    Comparator.reverseOrder()))
            .findFirst();
    }

    public List<DashboardView.CandidatePriority> refinementPriorities(
        List<DashboardView.WorkflowItem> workflows
    ) {
        return priorities(workflows).stream()
            .filter(priority -> priority.candidateWorkflow().hotfix() == null)
            .filter(priority -> !priority.candidateWorkflow().candidate().selectable()
                || priority.candidateWorkflow().candidate().confidence() < 0.8)
            .sorted(Comparator
                .comparingDouble((DashboardView.CandidatePriority priority) ->
                    priority.candidateWorkflow().candidate().confidence())
                .thenComparing(priority -> priority.workflow().storedAnalysis().createdAt(),
                    Comparator.reverseOrder()))
            .limit(5)
            .toList();
    }

    private List<DashboardView.CandidatePriority> priorities(
        List<DashboardView.WorkflowItem> workflows
    ) {
        return workflows.stream()
            .flatMap(workflow -> IntStream.range(0, workflow.candidateWorkflows().size())
                .mapToObj(index -> new DashboardView.CandidatePriority(
                    workflow,
                    workflow.candidateWorkflows().get(index),
                    index + 1,
                    priorityReason(workflow.candidateWorkflows().get(index).candidate())
                )))
            .toList();
    }

    private String priorityReason(DashboardView.Candidate candidate) {
        if ("INSUFFICIENT_EVIDENCE".equals(candidate.eligibility())) {
            return "증거와 코드 위치를 보강하면 자동 수정 가능성을 다시 판단할 수 있습니다.";
        }
        if ("HUMAN_ONLY".equals(candidate.eligibility())) {
            return "운영 판단이 필요한 후보라 사람 검토와 정밀 분석이 함께 필요합니다.";
        }
        return "자동 수정 후보지만 신뢰도가 낮아 정밀 분석을 먼저 권장합니다.";
    }
}
