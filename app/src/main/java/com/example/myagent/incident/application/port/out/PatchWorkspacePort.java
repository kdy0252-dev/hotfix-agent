package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import io.vavr.control.Either;

public interface PatchWorkspacePort {
    Either<IncidentFailure, Workspace> prepare(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId
    );

    Either<IncidentFailure, AppliedPatch> apply(Workspace workspace, Proposal proposal);

    Either<IncidentFailure, Workspace> refresh(Workspace workspace);

    Either<IncidentFailure, String> currentHead(Workspace workspace);

    Either<IncidentFailure, ReviewBranch> publishForHumanReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        String branchName
    );

    Either<IncidentFailure, AppliedPatch> reloadHumanChanges(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        String branchName
    );

    record ReviewBranch(String name, String url, String commit) {
    }
}
