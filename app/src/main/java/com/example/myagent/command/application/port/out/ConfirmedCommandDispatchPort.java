package com.example.myagent.command.application.port.out;

import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import io.vavr.control.Either;

public interface ConfirmedCommandDispatchPort {
    Either<CommandFailure, CommandExecution.Result> dispatch(
        InterpretedCommand command,
        String idempotencyKey
    );
}
