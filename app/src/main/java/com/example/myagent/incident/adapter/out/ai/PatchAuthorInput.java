package com.example.myagent.incident.adapter.out.ai;

import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.PatchInstruction;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;

public record PatchAuthorInput(
    BugCandidate candidate,
    Workspace workspace,
    int attempt,
    String previousFailure,
    PatchInstruction patchInstruction
) {
}
