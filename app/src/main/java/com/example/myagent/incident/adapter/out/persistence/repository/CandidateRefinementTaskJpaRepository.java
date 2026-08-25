package com.example.myagent.incident.adapter.out.persistence.repository;

import com.example.myagent.incident.adapter.out.persistence.entity.CandidateRefinementTaskEntity;
import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRefinementTaskJpaRepository
    extends JpaRepository<CandidateRefinementTaskEntity, String> {
    Optional<CandidateRefinementTaskEntity> findByAnalysisIdAndCandidateId(
        String analysisId,
        String candidateId
    );

    List<CandidateRefinementTaskEntity> findAllByAnalysisId(String analysisId);

    List<CandidateRefinementTaskEntity> findAllByStatusInOrderByUpdatedAtAsc(
        List<CandidateRefinementTask.Status> statuses
    );
}
