package com.example.myagent.global.configuration;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.integrations.jenkins")
public record JenkinsProperties(
    URI baseUrl,
    String rootJob,
    String username,
    String apiToken,
    boolean tlsVerify
) {
}
