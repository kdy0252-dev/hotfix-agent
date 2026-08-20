package com.example.myagent.incident.adapter.out.ai;

import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;

public record PatchReviewInput(BugCandidate candidate, AppliedPatch patch) {
}
