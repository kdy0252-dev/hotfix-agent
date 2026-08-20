package com.example.myagent.incident.application.domain.model.analysis;

import java.util.Map;

public record SourceContext(Map<String, String> files) {
    public SourceContext {
        files = files == null ? Map.of() : Map.copyOf(files);
    }
}
