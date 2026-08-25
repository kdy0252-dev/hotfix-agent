package com.example.myagent.incident.adapter.out.persistence;

import com.example.myagent.incident.adapter.out.persistence.entity.CandidateRefinementTaskEntity;
import com.example.myagent.incident.adapter.out.persistence.repository.CandidateRefinementTaskJpaRepository;
import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask;
import com.example.myagent.incident.application.port.out.CandidateRefinementTaskPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.List;
import java.util.Optional;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Adapter
@Component
public class JpaCandidateRefinementTaskAdapter implements CandidateRefinementTaskPort {
    private final CandidateRefinementTaskJpaRepository repository;

    public JpaCandidateRefinementTaskAdapter(CandidateRefinementTaskJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Either<IncidentFailure, CandidateRefinementTask> save(CandidateRefinementTask task) {
        return Try.of(() -> repository.save(CandidateRefinementTaskEntity.from(task)).toDomain())
            .toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, Optional<CandidateRefinementTask>> find(
        String analysisId,
        String candidateId
    ) {
        return Try.of(() -> repository.findByAnalysisIdAndCandidateId(analysisId, candidateId)
            .map(CandidateRefinementTaskEntity::toDomain)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, List<CandidateRefinementTask>> findByAnalysisId(
        String analysisId
    ) {
        return Try.of(() -> repository.findAllByAnalysisId(analysisId).stream()
            .map(CandidateRefinementTaskEntity::toDomain)
            .toList()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<IncidentFailure, List<CandidateRefinementTask>> findIncomplete() {
        return Try.of(() -> repository.findAllByStatusInOrderByUpdatedAtAsc(List.of(
                CandidateRefinementTask.Status.REQUESTED,
                CandidateRefinementTask.Status.RUNNING
            )).stream()
            .map(CandidateRefinementTaskEntity::toDomain)
            .toList()).toEither().mapLeft(this::failure);
    }

    private IncidentFailure failure(Throwable throwable) {
        return new IncidentFailure(
            "CANDIDATE_REFINEMENT_STATE_FAILURE",
            "정밀 AI 분석 상태를 처리하지 못했습니다."
        );
    }
}
