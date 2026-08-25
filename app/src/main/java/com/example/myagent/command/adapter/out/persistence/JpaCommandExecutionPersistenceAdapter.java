package com.example.myagent.command.adapter.out.persistence;

import com.example.myagent.command.adapter.out.persistence.entity.CommandExecutionEntity;
import com.example.myagent.command.adapter.out.persistence.repository.CommandExecutionJpaRepository;
import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.port.out.CommandExecutionStatePort;
import com.example.myagent.command.application.port.out.CommandFailure;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.Optional;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Adapter
@Component
public class JpaCommandExecutionPersistenceAdapter implements CommandExecutionStatePort {
    private final CommandExecutionJpaRepository repository;

    public JpaCommandExecutionPersistenceAdapter(CommandExecutionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Either<CommandFailure, CommandExecution> save(CommandExecution execution) {
        return Try.of(() -> repository.save(CommandExecutionEntity.from(execution)).toDomain())
            .toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<CommandFailure, Optional<CommandExecution>> findByIdempotencyKey(String key) {
        return Try.of(() -> repository.findByIdempotencyKey(key).map(CommandExecutionEntity::toDomain))
            .toEither().mapLeft(this::failure);
    }

    private CommandFailure failure(Throwable throwable) {
        return new CommandFailure(
            "COMMAND_EXECUTION_STATE_FAILURE",
            "명령 실행 상태를 처리하지 못했습니다."
        );
    }
}
