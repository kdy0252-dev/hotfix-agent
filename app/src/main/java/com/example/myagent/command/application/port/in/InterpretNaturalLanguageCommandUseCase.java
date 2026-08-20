package com.example.myagent.command.application.port.in;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;

public interface InterpretNaturalLanguageCommandUseCase {
    CommandInterpretation interpret(InterpretCommand command);

    record InterpretCommand(String text, String idempotencyKey) {
    }
}
