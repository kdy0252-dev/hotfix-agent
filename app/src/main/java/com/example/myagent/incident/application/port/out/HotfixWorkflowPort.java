package com.example.myagent.incident.application.port.out;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import io.vavr.control.Either;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface HotfixWorkflowPort {
    Either<IncidentFailure, HotfixResource> execute(
        AnalysisSession analysis,
        BugCandidate candidate,
        HotfixResource hotfix,
        Consumer<ProgressUpdate> progressReporter,
        BooleanSupplier cancelled
    );

    Either<IncidentFailure, HotfixResource> publishForHumanReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        HotfixResource hotfix
    );

    Either<IncidentFailure, HotfixResource> verifyHumanChanges(
        AnalysisSession analysis,
        BugCandidate candidate,
        HotfixResource hotfix,
        Consumer<ProgressUpdate> progressReporter,
        BooleanSupplier cancelled
    );

    record ProgressUpdate(
        HotfixResource.Status status,
        String branchName,
        HotfixResource.WorkflowStage stage,
        String message
    ) {
    }
}
