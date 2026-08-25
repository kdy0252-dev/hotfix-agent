package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.PatchInstruction;

public interface SelectCandidateUseCase {
    HotfixResource select(SelectionCommand command);

    record SelectionCommand(
        String analysisId,
        String candidateId,
        long analysisVersion,
        String idempotencyKey,
        PatchInstruction patchInstruction
    ) {
        public SelectionCommand {
            patchInstruction = patchInstruction == null ? PatchInstruction.none() : patchInstruction;
        }

        public SelectionCommand(
            String analysisId,
            String candidateId,
            long analysisVersion,
            String idempotencyKey
        ) {
            this(analysisId, candidateId, analysisVersion, idempotencyKey, PatchInstruction.none());
        }
    }
}
