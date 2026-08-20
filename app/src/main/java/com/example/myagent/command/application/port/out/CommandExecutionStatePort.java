package com.example.myagent.command.application.port.out;

import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import io.vavr.control.Either;
import java.util.Optional;

public interface CommandExecutionStatePort {
    Either<CommandFailure, CommandExecution> save(CommandExecution execution);

    Either<CommandFailure, Optional<CommandExecution>> findByIdempotencyKey(String key);
}
