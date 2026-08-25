package com.example.myagent.command.adapter.out.persistence.repository;

import com.example.myagent.command.adapter.out.persistence.entity.CommandInterpretationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandInterpretationJpaRepository
    extends JpaRepository<CommandInterpretationEntity, String> {
    Optional<CommandInterpretationEntity> findByIdempotencyKey(String idempotencyKey);
}
