package com.example.myagent.command.application.domain.model.command;

import java.util.Objects;

public record InterpretedCommand(CommandIntent intent, CommandParameters parameters) {

    public InterpretedCommand {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
    }
}
