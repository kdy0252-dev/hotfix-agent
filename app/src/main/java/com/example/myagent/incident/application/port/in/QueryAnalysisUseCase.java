package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import java.util.List;

public interface QueryAnalysisUseCase {
    AnalysisSession getAnalysis(String analysisId);

    List<BugCandidate> getCandidates(String analysisId);
}
