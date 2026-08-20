package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import io.vavr.control.Either;
import java.util.Optional;

public interface IncidentStatePort {
    Either<IncidentFailure, AnalysisSession> saveAnalysis(AnalysisEnvelope envelope);

    Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysis(String analysisId);

    Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysisByIdempotencyKey(String key);

    Either<IncidentFailure, HotfixResource> saveHotfix(HotfixEnvelope envelope);

    Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfix(String hotfixId);

    Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfixByIdempotencyKey(String key);

    record AnalysisEnvelope(
        int schemaVersion,
        String idempotencyKey,
        String requestHash,
        AnalysisSession session
    ) {
    }

    record HotfixEnvelope(
        int schemaVersion,
        String idempotencyKey,
        String requestHash,
        HotfixResource resource
    ) {
    }
}
