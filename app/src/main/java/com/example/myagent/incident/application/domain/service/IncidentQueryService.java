package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.QueryAnalysisUseCase;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IncidentQueryService implements QueryAnalysisUseCase {
    private final IncidentStatePort statePort;

    public IncidentQueryService(IncidentStatePort statePort) {
        this.statePort = statePort;
    }

    @Override
    public AnalysisSession getAnalysis(String analysisId) {
        return statePort.findAnalysis(analysisId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> new IncidentUseCaseException(
                "ANALYSIS_NOT_FOUND",
                "분석 결과를 찾을 수 없습니다."
            ))
            .session();
    }

    @Override
    public List<BugCandidate> getCandidates(String analysisId) {
        return getAnalysis(analysisId).result().candidates();
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
