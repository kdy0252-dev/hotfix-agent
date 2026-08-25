package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;

public interface RefineCandidateUseCase {
    AnalysisSession refine(RefinementCommand command);

    record RefinementCommand(String analysisId, long analysisVersion, String candidateId) {
    }
}
