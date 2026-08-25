package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;

public interface ManageHotfixUseCase {
    HotfixResource restart(RestartCommand command);

    HotfixResource publishHumanReviewBranch(String hotfixId);

    HotfixResource verifyHumanChanges(String hotfixId);

    void cancelAndDeleteHotfix(String hotfixId);

    void cancelAndDeleteWorkflow(String analysisId);

    record RestartCommand(String hotfixId, String idempotencyKey) {
    }
}
