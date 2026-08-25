package com.example.myagent.dashboard.application.domain.service.internal;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.global.annotation.InternalService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

@InternalService
public class DashboardWorkflowAssembler {
    public List<DashboardView.WorkflowItem> assemble(
        List<DashboardView.StoredAnalysis> analyses,
        List<DashboardView.HotfixProgress> hotfixes
    ) {
        var hotfixByCandidate = hotfixes.stream().collect(Collectors.toMap(
            hotfix -> new CandidateKey(
                hotfix.identity().analysisId(),
                hotfix.identity().candidateId()
            ),
            hotfix -> hotfix,
            (latest, ignored) -> latest,
            LinkedHashMap::new
        ));
        return analyses.stream()
            .filter(this::displayable)
            .map(analysis -> new DashboardView.WorkflowItem(
                analysis,
                analysis.analysis().candidates().stream()
                    .map(candidate -> new DashboardView.CandidateWorkflow(
                        candidate,
                        hotfixByCandidate.get(new CandidateKey(
                            analysis.analysis().identity().analysisId(),
                            candidate.candidateId()
                        ))
                    ))
                    .toList()
            ))
            .toList();
    }

    private boolean displayable(DashboardView.StoredAnalysis analysis) {
        return !"FAILED".equals(analysis.analysis().status())
            || !analysis.analysis().candidates().isEmpty();
    }

    private record CandidateKey(String analysisId, String candidateId) {
    }
}
