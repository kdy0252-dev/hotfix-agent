package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Publication;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Review;
import io.vavr.control.Either;

public interface PullRequestPort {
    Either<IncidentFailure, Publication> publishDraft(PublicationRequest request);

    record PublicationRequest(String hotfixId, IncidentArtifact incident, PatchArtifact patchData) {
        public AnalysisSession analysis() {
            return incident.analysis();
        }

        public BugCandidate candidate() {
            return incident.candidate();
        }

        public AppliedPatch patch() {
            return patchData.patch();
        }

        public Verification verification() {
            return patchData.verification();
        }

        public Review review() {
            return patchData.review();
        }
    }

    record IncidentArtifact(AnalysisSession analysis, BugCandidate candidate) {
    }

    record PatchArtifact(
        AppliedPatch patch,
        Verification verification,
        Review review
    ) {
    }
}
