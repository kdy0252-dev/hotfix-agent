package com.example.myagent.command.adapter.out.ai;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretationDraft;
import com.example.myagent.command.application.port.out.CommandFailure;
import com.example.myagent.command.application.port.out.NaturalLanguageInterpreterPort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class EmbabelNaturalLanguageInterpreterAdapter implements NaturalLanguageInterpreterPort {
    private final AgentPlatform agentPlatform;

    public EmbabelNaturalLanguageInterpreterAdapter(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @Override
    public Either<CommandFailure, CommandInterpretationDraft> interpret(String redactedText) {
        return Try.of(() -> runAgent(redactedText))
            .toEither()
            .mapLeft(exception -> new CommandFailure(
                "COMMAND_INTERPRETATION_FAILED",
                "Embabel이 자연어 명령을 해석하지 못했습니다."
            ));
    }

    private CommandInterpretationDraft runAgent(String redactedText) {
        var invocation = AgentInvocation.create(agentPlatform, CommandInterpretationDraft.class);
        var process = invocation.run(
            new NaturalLanguageCommandAgent.NaturalLanguageInput(redactedText)
        );
        return process.resultOfType(CommandInterpretationDraft.class);
    }
}
