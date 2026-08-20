package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import io.vavr.control.Either;
import java.util.List;

public interface CandidateAnalysisPort {
    Either<IncidentFailure, List<BugCandidate>> analyze(
        AnalysisEvidence evidence,
        SourceRevision sourceRevision,
        SourceContext sourceContext
    );
}
