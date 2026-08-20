package com.example.myagent.global.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.runtime")
public record AgentRuntimeProperties(
    Mode mode,
    Path fmsRepositoryPath,
    Duration analysisTtl
) {
    public enum Mode {
        REPORT_ONLY,
        DRAFT_PR
    }
}
