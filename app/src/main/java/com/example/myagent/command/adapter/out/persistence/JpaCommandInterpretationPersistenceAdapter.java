package com.example.myagent.command.adapter.out.persistence;

import com.example.myagent.command.adapter.out.persistence.entity.CommandInterpretationEntity;
import com.example.myagent.command.adapter.out.persistence.repository.CommandInterpretationJpaRepository;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.port.out.CommandFailure;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.List;
import java.util.Optional;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Adapter
@Component
public class JpaCommandInterpretationPersistenceAdapter implements CommandInterpretationStatePort {
    private final CommandInterpretationJpaRepository repository;

    public JpaCommandInterpretationPersistenceAdapter(CommandInterpretationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Either<CommandFailure, CommandInterpretation> save(StateEntry entry) {
        return Try.of(() -> repository.save(CommandInterpretationEntity.from(entry))
            .toDomain().interpretation()).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<CommandFailure, Optional<CommandInterpretation>> findById(String interpretationId) {
        return Try.of(() -> repository.findById(interpretationId)
            .map(CommandInterpretationEntity::toDomain)
            .map(StateEntry::interpretation)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<CommandFailure, Optional<StateEntry>> findByIdempotencyKey(String idempotencyKey) {
        return Try.of(() -> repository.findByIdempotencyKey(idempotencyKey)
            .map(CommandInterpretationEntity::toDomain)).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional
    public Either<CommandFailure, CommandInterpretation> markExecuted(String interpretationId) {
        return Try.of(() -> {
            var entity = repository.findById(interpretationId).orElseThrow();
            entity.markExecuted();
            return repository.save(entity).toDomain().interpretation();
        }).toEither().mapLeft(this::failure);
    }

    @Override
    @Transactional(readOnly = true)
    public Either<CommandFailure, List<StateEntry>> findIncomplete() {
        return Try.of(() -> repository.findAllByStatusInOrderByCreatedAtAsc(List.of(
                InterpretationStatus.INTERPRETATION_REQUESTED,
                InterpretationStatus.INTERPRETING
            )).stream()
            .map(CommandInterpretationEntity::toDomain)
            .toList()).toEither().mapLeft(this::failure);
    }

    private CommandFailure failure(Throwable throwable) {
        return new CommandFailure("COMMAND_STATE_FAILURE", "자연어 명령 상태를 처리하지 못했습니다.");
    }
}
