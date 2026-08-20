package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import io.vavr.control.Either;

public interface ObservabilityEvidencePort {
    Either<IncidentFailure, AnalysisEvidence.Observability> collect(
        AnalysisRequest.Observability request
    );
}
