package com.example.myagent.incident.adapter.out.persistence.repository;

import com.example.myagent.incident.adapter.out.persistence.entity.IncidentHotfixEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentHotfixJpaRepository extends JpaRepository<IncidentHotfixEntity, String> {
    Optional<IncidentHotfixEntity> findByIdempotencyKey(String idempotencyKey);

    List<IncidentHotfixEntity> findAllByOrderByUpdatedAtDesc();

    void deleteAllByAnalysisId(String analysisId);
}
