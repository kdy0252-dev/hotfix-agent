package com.example.myagent.incident.adapter.in.event;

import com.example.myagent.incident.application.port.in.RecoverAnalysisUseCase;
import com.example.myagent.incident.application.port.in.RecoverHotfixUseCase;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class InterruptedWorkRecoveryListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        InterruptedWorkRecoveryListener.class
    );

    private final RecoverAnalysisUseCase recoverAnalysisUseCase;
    private final RecoverHotfixUseCase recoverHotfixUseCase;

    public InterruptedWorkRecoveryListener(
        RecoverAnalysisUseCase recoverAnalysisUseCase,
        RecoverHotfixUseCase recoverHotfixUseCase
    ) {
        this.recoverAnalysisUseCase = recoverAnalysisUseCase;
        this.recoverHotfixUseCase = recoverHotfixUseCase;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedWork() {
        Try.of(recoverAnalysisUseCase::recoverInterruptedAnalyses)
            .onSuccess(count -> LOGGER.info("Submitted {} interrupted analyses", count))
            .onFailure(exception -> LOGGER.error("Analysis recovery scan failed", exception));
        Try.of(recoverHotfixUseCase::recoverInterruptedHotfixes)
            .onSuccess(count -> LOGGER.info("Submitted {} interrupted hotfixes", count))
            .onFailure(exception -> LOGGER.error("Hotfix recovery scan failed", exception));
    }
}
