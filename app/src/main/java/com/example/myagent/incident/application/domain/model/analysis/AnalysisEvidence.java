package com.example.myagent.incident.application.domain.model.analysis;

import java.util.List;

public sealed interface AnalysisEvidence permits AnalysisEvidence.Jenkins,
    AnalysisEvidence.Observability {

    List<String> evidenceRefs();

    record Jenkins(
        String buildUrl,
        String revision,
        List<String> relevantLogLines,
        String testReport,
        List<String> evidenceRefs
    ) implements AnalysisEvidence {
    }

    record Observability(
        String namespace,
        String serviceName,
        String metrics,
        String traces,
        String logs,
        String alerts,
        List<String> evidenceRefs
    ) implements AnalysisEvidence {
    }
}
