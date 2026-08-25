package com.example.myagent.command.application.domain.service.moduleapi;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.port.in.ExecuteNaturalLanguageCommandUseCase;
import com.example.myagent.command.application.port.in.GetCommandInterpretationUseCase;
import com.example.myagent.command.application.port.in.InterpretNaturalLanguageCommandUseCase;
import com.example.myagent.orchestrator.NaturalLanguageDashboardGateway;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NaturalLanguageDashboardModuleApiService
    implements NaturalLanguageDashboardGateway {
    private final InterpretNaturalLanguageCommandUseCase interpretUseCase;
    private final ExecuteNaturalLanguageCommandUseCase executeUseCase;
    private final GetCommandInterpretationUseCase getUseCase;

    public NaturalLanguageDashboardModuleApiService(
        InterpretNaturalLanguageCommandUseCase interpretUseCase,
        ExecuteNaturalLanguageCommandUseCase executeUseCase,
        GetCommandInterpretationUseCase getUseCase
    ) {
        this.interpretUseCase = interpretUseCase;
        this.executeUseCase = executeUseCase;
        this.getUseCase = getUseCase;
    }

    @Override
    public InterpretationPreview interpretation(String interpretationId) {
        return preview(getUseCase.get(interpretationId));
    }

    @Override
    public InterpretationPreview interpret(InterpretationCommand command) {
        var interpretation = interpretUseCase.interpret(
            new InterpretNaturalLanguageCommandUseCase.InterpretCommand(
                command.text(),
                command.idempotencyKey()
            )
        );
        return preview(interpretation);
    }

    @Override
    public ExecutionResult execute(ExecutionCommand command) {
        var execution = executeUseCase.execute(
            new ExecuteNaturalLanguageCommandUseCase.ExecutionCommand(
                command.interpretationId(),
                command.version(),
                command.commandHash(),
                command.idempotencyKey()
            )
        );
        return new ExecutionResult(
            execution.result().resourceId(),
            execution.result().status(),
            execution.result().statusUrl(),
            execution.result().itemIds()
        );
    }

    private InterpretationPreview preview(CommandInterpretation interpretation) {
        var decision = interpretation.decision();
        String intent = decision.command() == null
            ? null : decision.command().intent().name();
        String parameters = decision.command() == null
            ? null : decision.command().parameters().toString();
        return new InterpretationPreview(
            new Metadata(
                interpretation.metadata().interpretationId(),
                interpretation.metadata().version(),
                interpretation.metadata().timing().expiresAt()
            ),
            new Decision(
                decision.status().name(),
                intent,
                parameters,
                decision.feedback() == null
                    ? List.of() : decision.feedback().clarificationQuestions(),
                decision.feedback() == null
                    ? null : decision.feedback().rejectionMessage(),
                decision.commandHash()
            )
        );
    }
}
