package com.example.myagent.dashboard.application.port.out;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import io.vavr.control.Either;

public interface NaturalLanguageDashboardPort {
    Either<DashboardFailure, DashboardView.InterpretationPreview> interpret(
        InterpretationCommand command
    );

    Either<DashboardFailure, DashboardView.ExecutionResult> execute(ExecutionCommand command);

    record InterpretationCommand(String text, String idempotencyKey) {
    }

    record ExecutionCommand(
        String interpretationId,
        long version,
        String commandHash,
        String idempotencyKey
    ) {
    }
}
