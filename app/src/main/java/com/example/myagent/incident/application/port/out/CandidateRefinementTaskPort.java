package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask;
import io.vavr.control.Either;
import java.util.List;
import java.util.Optional;

public interface CandidateRefinementTaskPort {
    Either<IncidentFailure, CandidateRefinementTask> save(CandidateRefinementTask task);

    Either<IncidentFailure, Optional<CandidateRefinementTask>> find(
        String analysisId,
        String candidateId
    );

    Either<IncidentFailure, List<CandidateRefinementTask>> findByAnalysisId(String analysisId);

    Either<IncidentFailure, List<CandidateRefinementTask>> findIncomplete();
}
