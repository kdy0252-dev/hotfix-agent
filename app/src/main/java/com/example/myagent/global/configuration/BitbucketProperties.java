package com.example.myagent.global.configuration;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.integrations.bitbucket")
public record BitbucketProperties(
    URI baseUrl,
    URI gitBaseUrl,
    String workspace,
    String repository,
    String token
) {
}
