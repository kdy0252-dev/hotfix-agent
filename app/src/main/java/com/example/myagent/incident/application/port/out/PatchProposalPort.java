package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.PatchInstruction;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import io.vavr.control.Either;

public interface PatchProposalPort {
    Either<IncidentFailure, Proposal> propose(PatchRequest request);

    record PatchRequest(
        BugCandidate candidate,
        Workspace workspace,
        int attempt,
        String previousFailure,
        PatchInstruction patchInstruction
    ) {
    }
}
