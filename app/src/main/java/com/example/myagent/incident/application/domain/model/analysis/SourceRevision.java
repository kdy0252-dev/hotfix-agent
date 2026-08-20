package com.example.myagent.incident.application.domain.model.analysis;

public record SourceRevision(
    String commit,
    String destinationBranch,
    String provenance
) {
}
