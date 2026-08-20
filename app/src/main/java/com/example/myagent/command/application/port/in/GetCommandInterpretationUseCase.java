package com.example.myagent.command.application.port.in;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;

public interface GetCommandInterpretationUseCase {
    CommandInterpretation get(String interpretationId);
}
