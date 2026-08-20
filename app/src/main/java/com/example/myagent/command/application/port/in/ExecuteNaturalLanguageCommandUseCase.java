package com.example.myagent.command.application.port.in;

import com.example.myagent.command.application.domain.model.execution.CommandExecution;

public interface ExecuteNaturalLanguageCommandUseCase {
    CommandExecution execute(ExecutionCommand command);

    record ExecutionCommand(
        String interpretationId,
        long interpretationVersion,
        String commandHash,
        String idempotencyKey
    ) {
    }
}
