package com.example.myagent.command.adapter.out.persistence.repository;

import com.example.myagent.command.adapter.out.persistence.entity.CommandExecutionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandExecutionJpaRepository extends JpaRepository<CommandExecutionEntity, String> {
    Optional<CommandExecutionEntity> findByIdempotencyKey(String idempotencyKey);
}
