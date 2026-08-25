package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.service.internal.HotfixRecoveryExecutor;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.RecoverHotfixUseCase;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import org.springframework.stereotype.Service;

@Service
public class HotfixRecoveryService implements RecoverHotfixUseCase {
    private final IncidentStatePort statePort;
    private final HotfixRecoveryExecutor recoveryExecutor;

    public HotfixRecoveryService(
        IncidentStatePort statePort,
        HotfixRecoveryExecutor recoveryExecutor
    ) {
        this.statePort = statePort;
        this.recoveryExecutor = recoveryExecutor;
    }

    @Override
    public int recoverInterruptedHotfixes() {
        var interrupted = statePort.findAllHotfixes()
            .getOrElseThrow(this::failure)
            .stream()
            .filter(recoveryExecutor::isRecoverable)
            .toList();
        interrupted.forEach(recoveryExecutor::recover);
        return interrupted.size();
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
