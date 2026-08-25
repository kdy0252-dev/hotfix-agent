package com.example.myagent.incident.adapter.out.persistence.repository;

import com.example.myagent.incident.adapter.out.persistence.entity.IncidentAnalysisEntity;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentAnalysisJpaRepository extends JpaRepository<IncidentAnalysisEntity, String> {
    Optional<IncidentAnalysisEntity> findByIdempotencyKey(String idempotencyKey);

    List<IncidentAnalysisEntity> findTop20ByOrderByUpdatedAtDesc();

    List<IncidentAnalysisEntity> findAllByStatusInOrderByUpdatedAtAsc(
        List<AnalysisSession.Status> statuses
    );
}
