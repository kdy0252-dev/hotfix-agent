package com.example.myagent.dashboard.adapter.out.module;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.dashboard.application.port.out.DashboardFailure;
import com.example.myagent.dashboard.application.port.out.NaturalLanguageDashboardPort;
import com.example.myagent.orchestrator.NaturalLanguageDashboardGateway;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class NaturalLanguageDashboardModuleAdapter implements NaturalLanguageDashboardPort {
    private final NaturalLanguageDashboardGateway gateway;

    public NaturalLanguageDashboardModuleAdapter(NaturalLanguageDashboardGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Either<DashboardFailure, DashboardView.InterpretationPreview> interpretation(
        String interpretationId
    ) {
        return Try.of(() -> preview(gateway.interpretation(interpretationId)))
            .toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.InterpretationPreview> interpret(
        InterpretationCommand command
    ) {
        return Try.of(() -> preview(gateway.interpret(
            new NaturalLanguageDashboardGateway.InterpretationCommand(
                command.text(),
                command.idempotencyKey()
            )
        ))).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<DashboardFailure, DashboardView.ExecutionResult> execute(
        ExecutionCommand command
    ) {
        return Try.of(() -> execution(gateway.execute(
            new NaturalLanguageDashboardGateway.ExecutionCommand(
                command.interpretationId(),
                command.version(),
                command.commandHash(),
                command.idempotencyKey()
            )
        ))).toEither().mapLeft(this::failure);
    }

    private DashboardView.InterpretationPreview preview(
        NaturalLanguageDashboardGateway.InterpretationPreview view
    ) {
        return new DashboardView.InterpretationPreview(
            new DashboardView.Metadata(
                view.metadata().interpretationId(),
                view.metadata().version(),
                view.metadata().expiresAt()
            ),
            new DashboardView.Decision(
                view.decision().status(),
                view.decision().intent(),
                view.decision().parameterSummary(),
                view.decision().clarificationQuestions(),
                view.decision().rejectionMessage(),
                view.decision().commandHash()
            )
        );
    }

    private DashboardView.ExecutionResult execution(
        NaturalLanguageDashboardGateway.ExecutionResult view
    ) {
        return new DashboardView.ExecutionResult(
            view.resourceId(),
            view.status(),
            view.statusUrl(),
            view.itemIds()
        );
    }

    private DashboardFailure failure(Throwable throwable) {
        return new DashboardFailure("NATURAL_LANGUAGE_DASHBOARD_FAILED", throwable.getMessage());
    }
}
