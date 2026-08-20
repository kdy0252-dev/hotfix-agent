package com.example.myagent.incident.adapter.out.ai;

import java.util.List;

public record PatchReviewResult(boolean approved, String summary, List<String> findings) {
    public PatchReviewResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
