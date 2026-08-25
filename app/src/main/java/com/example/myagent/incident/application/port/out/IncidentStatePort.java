package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import io.vavr.control.Either;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IncidentStatePort {
    Either<IncidentFailure, AnalysisSession> saveAnalysis(AnalysisEnvelope envelope);

    Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysis(String analysisId);

    Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysisByIdempotencyKey(String key);

    Either<IncidentFailure, List<AnalysisEnvelope>> findRecentAnalyses();

    Either<IncidentFailure, List<AnalysisEnvelope>> findIncompleteAnalyses();

    Either<IncidentFailure, HotfixResource> saveHotfix(HotfixEnvelope envelope);

    Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfix(String hotfixId);

    Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfixByIdempotencyKey(String key);

    Either<IncidentFailure, List<HotfixEnvelope>> findAllHotfixes();

    Either<IncidentFailure, Boolean> deleteHotfix(String hotfixId);

    Either<IncidentFailure, Boolean> deleteWorkflow(String analysisId);

    record AnalysisEnvelope(
        int schemaVersion,
        String idempotencyKey,
        String requestHash,
        AnalysisSession session,
        AnalysisRequest request
    ) {
        public AnalysisEnvelope(
            int schemaVersion,
            String idempotencyKey,
            String requestHash,
            AnalysisSession session
        ) {
            this(schemaVersion, idempotencyKey, requestHash, session, null);
        }
    }

    record HotfixEnvelope(
        int schemaVersion,
        String idempotencyKey,
        String requestHash,
        HotfixResource resource,
        Instant updatedAt
    ) {
        public HotfixEnvelope(
            int schemaVersion,
            String idempotencyKey,
            String requestHash,
            HotfixResource resource
        ) {
            this(schemaVersion, idempotencyKey, requestHash, resource, null);
        }
    }
}
