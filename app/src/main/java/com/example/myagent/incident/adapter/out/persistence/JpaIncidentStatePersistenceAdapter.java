package com.example.myagent.incident.adapter.out.persistence;

import com.example.myagent.incident.adapter.out.persistence.entity.IncidentAnalysisEntity;
import com.example.myagent.incident.adapter.out.persistence.entity.IncidentHotfixEntity;
import com.example.myagent.incident.adapter.out.persistence.repository.IncidentAnalysisJpaRepository;
import com.example.myagent.incident.adapter.out.persistence.repository.IncidentHotfixJpaRepository;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.List;
import java.util.Optional;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Adapter
@Component
public class JpaIncidentStatePersistenceAdapter implements IncidentStatePort {
    private final IncidentAnalysisJpaRepository analysisRepository;
    private final IncidentHotfixJpaRepository hotfixRepository;

    public JpaIncidentStatePersistenceAdapter(
        IncidentAnalysisJpaRepository analysisRepository,
        IncidentHotfixJpaRepository hotfixRepository
    ) {
        this.analysisRepository = analysisRepository;
        this.hotfixRepository = hotfixRepository;
    }

    @Override
    @Transactional
    public Either<IncidentFailure, AnalysisSession> saveAnalysis(AnalysisEnvelope envelope) {
        return Try.of(() -> analysisRepository.save(IncidentAnalysisEntity.from(envelope))
            .toDomain().session()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysis(String analysisId) {
        return Try.of(() -> analysisRepository.findById(analysisId)
            .map(IncidentAnalysisEntity::toDomain)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysisByIdempotencyKey(
        String key
    ) {
        return Try.of(() -> analysisRepository.findByIdempotencyKey(key)
            .map(IncidentAnalysisEntity::toDomain)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, List<AnalysisEnvelope>> findRecentAnalyses() {
        return Try.of(() -> analysisRepository.findTop20ByOrderByUpdatedAtDesc().stream()
            .map(IncidentAnalysisEntity::toDomain)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, List<AnalysisEnvelope>> findIncompleteAnalyses() {
        var statuses = List.of(
            AnalysisSession.Status.ANALYSIS_REQUESTED,
            AnalysisSession.Status.ANALYZING
        );
        return Try.of(() -> analysisRepository.findAllByStatusInOrderByUpdatedAtAsc(statuses)
            .stream()
            .map(IncidentAnalysisEntity::toDomain)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional
    public Either<IncidentFailure, HotfixResource> saveHotfix(HotfixEnvelope envelope) {
        return Try.of(() -> hotfixRepository.save(IncidentHotfixEntity.from(envelope))
            .toDomain().resource()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfix(String hotfixId) {
        return Try.of(() -> hotfixRepository.findById(hotfixId)
            .map(IncidentHotfixEntity::toDomain)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfixByIdempotencyKey(String key) {
        return Try.of(() -> hotfixRepository.findByIdempotencyKey(key)
            .map(IncidentHotfixEntity::toDomain)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, List<HotfixEnvelope>> findAllHotfixes() {
        return Try.of(() -> hotfixRepository.findAllByOrderByUpdatedAtDesc().stream()
            .map(IncidentHotfixEntity::toDomain)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional
    public Either<IncidentFailure, Boolean> deleteHotfix(String hotfixId) {
        return Try.of(() -> {
            var entity = hotfixRepository.findById(hotfixId);
            entity.ifPresent(hotfixRepository::delete);
            return entity.isPresent();
        }).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional
    public Either<IncidentFailure, Boolean> deleteWorkflow(String analysisId) {
        return Try.of(() -> {
            var entity = analysisRepository.findById(analysisId);
            hotfixRepository.deleteAllByAnalysisId(analysisId);
            entity.ifPresent(analysisRepository::delete);
            return entity.isPresent();
        }).toEither().mapLeft(this::failure);
    }

    private IncidentFailure failure(Throwable throwable) {
        return new IncidentFailure("INCIDENT_STATE_FAILURE", "장애 분석 상태를 처리하지 못했습니다.");
    }
}
