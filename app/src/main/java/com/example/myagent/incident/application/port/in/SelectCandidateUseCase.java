package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;

public interface SelectCandidateUseCase {
    HotfixResource select(SelectionCommand command);

    record SelectionCommand(
        String analysisId,
        String candidateId,
        long analysisVersion,
        String idempotencyKey
    ) {
    }
}
