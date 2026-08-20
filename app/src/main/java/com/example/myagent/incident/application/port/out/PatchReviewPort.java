package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Review;
import io.vavr.control.Either;

public interface PatchReviewPort {
    Either<IncidentFailure, Review> review(BugCandidate candidate, AppliedPatch patch);
}
