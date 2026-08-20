package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import io.vavr.control.Either;

public interface VerificationPort {
    Either<IncidentFailure, Verification> runFocused(AppliedPatch patch, int attempt);

    Either<IncidentFailure, Verification> runParity(AppliedPatch patch, int focusedAttempts);
}
