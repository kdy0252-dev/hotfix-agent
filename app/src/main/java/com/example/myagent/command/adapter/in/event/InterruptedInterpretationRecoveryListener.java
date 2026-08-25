package com.example.myagent.command.adapter.in.event;

import com.example.myagent.command.application.port.in.RecoverNaturalLanguageInterpretationUseCase;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class InterruptedInterpretationRecoveryListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        InterruptedInterpretationRecoveryListener.class
    );

    private final RecoverNaturalLanguageInterpretationUseCase recoveryUseCase;

    public InterruptedInterpretationRecoveryListener(
        RecoverNaturalLanguageInterpretationUseCase recoveryUseCase
    ) {
        this.recoveryUseCase = recoveryUseCase;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedInterpretations() {
        Try.of(recoveryUseCase::recoverInterruptedInterpretations)
            .onSuccess(count -> LOGGER.info("Submitted {} interrupted interpretations", count))
            .onFailure(exception -> LOGGER.error("Interpretation recovery scan failed", exception));
    }
}
