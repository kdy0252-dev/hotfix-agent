package com.example.myagent.incident.application.domain.model.hotfix;

import java.util.List;
import java.util.Map;

public final class PatchArtifacts {
    private PatchArtifacts() {
    }

    public record Proposal(String summary, List<FileUpdate> updates) {
        public Proposal {
            updates = updates == null ? List.of() : List.copyOf(updates);
        }
    }

    public record FileUpdate(String path, String content, String reason) {
    }

    public record Workspace(
        String worktreePath,
        String branchName,
        String baseCommit,
        Map<String, String> sourceFiles
    ) {
        public Workspace {
            sourceFiles = Map.copyOf(sourceFiles);
        }
    }

    public record AppliedPatch(Workspace workspace, ChangeSummary changes, String patchCommit) {
    }

    public record ChangeSummary(List<String> files, int changedLines) {
        public ChangeSummary {
            files = List.copyOf(files);
        }
    }

    public record Review(boolean approved, String summary, List<String> findings) {
        public Review {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    public record Publication(String pullRequestUrl, String ciJobUrl) {
    }
}
