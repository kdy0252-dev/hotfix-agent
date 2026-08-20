package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import io.vavr.control.Either;

public interface HotfixWorkflowPort {
    Either<IncidentFailure, HotfixResource> execute(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId
    );
}
