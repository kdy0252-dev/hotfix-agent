package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import io.vavr.control.Either;

public interface SourceContextPort {
    Either<IncidentFailure, SourceContext> read(
        AnalysisEvidence evidence,
        SourceRevision sourceRevision
    );
}
