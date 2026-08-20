package com.example.myagent.global.configuration;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.verification.parity")
public record ParityProfileProperties(
    Map<String, Integer> approvedProfiles,
    int maxPatchRetries
) {
    public ParityProfileProperties {
        approvedProfiles = approvedProfiles == null ? Map.of() : Map.copyOf(approvedProfiles);
    }
}
