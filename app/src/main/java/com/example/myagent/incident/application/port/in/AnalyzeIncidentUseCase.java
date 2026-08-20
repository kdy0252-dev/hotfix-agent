package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;

public interface AnalyzeIncidentUseCase {
    AnalysisSession analyzeJenkins(AnalysisCommand<AnalysisRequest.Jenkins> command);

    AnalysisSession analyzeObservability(AnalysisCommand<AnalysisRequest.Observability> command);

    record AnalysisCommand<T extends AnalysisRequest>(T request, String idempotencyKey) {
    }
}
