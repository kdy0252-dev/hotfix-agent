package com.example.myagent.command.adapter.out.persistence.repository;

import com.example.myagent.command.adapter.out.persistence.entity.CommandInterpretationEntity;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandInterpretationJpaRepository
    extends JpaRepository<CommandInterpretationEntity, String> {
    Optional<CommandInterpretationEntity> findByIdempotencyKey(String idempotencyKey);

    List<CommandInterpretationEntity> findAllByStatusInOrderByCreatedAtAsc(
        List<InterpretationStatus> statuses
    );
}
