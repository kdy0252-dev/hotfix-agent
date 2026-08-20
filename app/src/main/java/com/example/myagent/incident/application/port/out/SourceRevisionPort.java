package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import io.vavr.control.Either;

public interface SourceRevisionPort {
    Either<IncidentFailure, SourceRevision> resolve(SourceSpec source);
}
