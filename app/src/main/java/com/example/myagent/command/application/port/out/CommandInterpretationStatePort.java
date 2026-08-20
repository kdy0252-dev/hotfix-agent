package com.example.myagent.command.application.port.out;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import io.vavr.control.Either;
import java.util.Optional;

public interface CommandInterpretationStatePort {
    Either<CommandFailure, CommandInterpretation> save(StateEntry entry);

    Either<CommandFailure, Optional<CommandInterpretation>> findById(String interpretationId);

    Either<CommandFailure, Optional<StateEntry>> findByIdempotencyKey(String idempotencyKey);

    Either<CommandFailure, CommandInterpretation> markExecuted(String interpretationId);

    record StateEntry(
        String idempotencyKey,
        String requestBodyHash,
        CommandInterpretation interpretation
    ) {
    }
}
