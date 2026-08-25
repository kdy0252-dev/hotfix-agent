package com.example.myagent.command.application.port.out;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import io.vavr.control.Either;
import java.util.List;
import java.util.Optional;

public interface CommandInterpretationStatePort {
    Either<CommandFailure, CommandInterpretation> save(StateEntry entry);

    Either<CommandFailure, Optional<CommandInterpretation>> findById(String interpretationId);

    Either<CommandFailure, Optional<StateEntry>> findByIdempotencyKey(String idempotencyKey);

    Either<CommandFailure, CommandInterpretation> markExecuted(String interpretationId);

    Either<CommandFailure, List<StateEntry>> findIncomplete();

    record StateEntry(
        String idempotencyKey,
        RequestPayload request,
        CommandInterpretation interpretation
    ) {
    }

    record RequestPayload(String bodyHash, String redactedText) {
    }
}
