package com.example.myagent.incident.adapter.out.ai;

import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.FileUpdate;
import java.util.List;

public record PatchProposalResult(String summary, List<FileUpdate> updates) {
    public PatchProposalResult {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }
}
