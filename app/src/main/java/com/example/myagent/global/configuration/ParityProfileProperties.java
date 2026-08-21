package com.example.myagent.global.configuration;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.verification.parity")
public record ParityProfileProperties(
    Map<String, Integer> approvedProfiles,
    ExecutionLimits limits,
    Path newmanWorkspaceRoot
) {
    public ParityProfileProperties {
        approvedProfiles = approvedProfiles == null ? Map.of() : Map.copyOf(approvedProfiles);
        if (limits == null) {
            throw new IllegalArgumentException("Parity execution limits are required");
        }
        if (newmanWorkspaceRoot == null) {
            throw new IllegalArgumentException("Newman workspace root is required");
        }
    }

    public record ExecutionLimits(int maxPatchRetries, int maxWorkers) {
        public ExecutionLimits {
            if (maxPatchRetries < 0 || maxPatchRetries > 2) {
                throw new IllegalArgumentException("Patch retries must be between zero and two");
            }
            if (maxWorkers < 1 || maxWorkers > 6) {
                throw new IllegalArgumentException("Parity workers must be between one and six");
            }
        }
    }
}
