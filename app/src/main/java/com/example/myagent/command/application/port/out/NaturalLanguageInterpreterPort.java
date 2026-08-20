package com.example.myagent.command.application.port.out;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretationDraft;
import io.vavr.control.Either;

public interface NaturalLanguageInterpreterPort {
    Either<CommandFailure, CommandInterpretationDraft> interpret(String redactedText);
}
