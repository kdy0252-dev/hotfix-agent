package com.example.myagent.incident.application.domain.service.moduleapi;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase.AnalysisCommand;
import com.example.myagent.incident.application.port.in.QueryAnalysisUseCase;
import com.example.myagent.incident.application.port.in.QueryHotfixUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import com.example.myagent.orchestrator.IncidentCommandGateway;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IncidentCommandModuleApiService implements IncidentCommandGateway {
    private final AnalyzeIncidentUseCase analyzeUseCase;
    private final QueryAnalysisUseCase queryAnalysisUseCase;
    private final SelectCandidateUseCase selectCandidateUseCase;
    private final QueryHotfixUseCase queryHotfixUseCase;

    public IncidentCommandModuleApiService(
        AnalyzeIncidentUseCase analyzeUseCase,
        QueryAnalysisUseCase queryAnalysisUseCase,
        SelectCandidateUseCase selectCandidateUseCase,
        QueryHotfixUseCase queryHotfixUseCase
    ) {
        this.analyzeUseCase = analyzeUseCase;
        this.queryAnalysisUseCase = queryAnalysisUseCase;
        this.selectCandidateUseCase = selectCandidateUseCase;
        this.queryHotfixUseCase = queryHotfixUseCase;
    }

    @Override
    public ResourceResult analyzeJenkins(JenkinsCommand command) {
        var session = analyzeUseCase.analyzeJenkins(new AnalysisCommand<>(
            new AnalysisRequest.Jenkins(
                command.jobPath(),
                command.buildNumber(),
                source(command.source())
            ),
            command.idempotencyKey()
        ));
        return analysisResult(session.identity().analysisId(), session.result().status().name());
    }

    @Override
    public ResourceResult analyzeObservability(ObservabilityCommand command) {
        var session = analyzeUseCase.analyzeObservability(new AnalysisCommand<>(
            new AnalysisRequest.Observability(
                new AnalysisRequest.TimeRange(command.startAt(), command.endAt()),
                AnalysisRequest.Environment.valueOf(command.environment()),
                source(command.source())
            ),
            command.idempotencyKey()
        ));
        return analysisResult(session.identity().analysisId(), session.result().status().name());
    }

    @Override
    public ResourceResult listCandidates(String analysisId) {
        List<String> candidateIds = queryAnalysisUseCase.getCandidates(analysisId).stream()
            .map(candidate -> candidate.identity().candidateId())
            .toList();
        return new ResourceResult(
            analysisId,
            "CANDIDATES_READY",
            "/api/v1/analyses/" + analysisId + "/candidates",
            candidateIds
        );
    }

    @Override
    public ResourceResult selectCandidate(SelectionCommand command) {
        var resource = selectCandidateUseCase.select(
            new SelectCandidateUseCase.SelectionCommand(
                command.analysisId(),
                command.candidateId(),
                command.analysisVersion(),
                command.idempotencyKey(),
                HotfixResource.PatchInstruction.from(command.patchInstruction())
            )
        );
        return hotfixResult(resource.identity().hotfixId(), resource.progress().status().name());
    }

    @Override
    public ResourceResult getHotfix(String hotfixId) {
        var resource = queryHotfixUseCase.getHotfix(hotfixId);
        return hotfixResult(hotfixId, resource.progress().status().name());
    }

    @Override
    public ResourceResult refreshCiStatus(String hotfixId) {
        var resource = queryHotfixUseCase.refreshCiStatus(hotfixId);
        return hotfixResult(hotfixId, resource.progress().status().name());
    }

    private SourceSpec source(Source commandSource) {
        return "BRANCH".equals(commandSource.type())
            ? SourceSpec.branch(commandSource.branchName())
            : SourceSpec.pullRequest(commandSource.pullRequestId());
    }

    private ResourceResult analysisResult(String analysisId, String status) {
        return new ResourceResult(
            analysisId,
            status,
            "/api/v1/analyses/" + analysisId,
            List.of()
        );
    }

    private ResourceResult hotfixResult(String hotfixId, String status) {
        return new ResourceResult(
            hotfixId,
            status,
            "/api/v1/hotfixes/" + hotfixId,
            List.of()
        );
    }
}
