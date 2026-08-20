package com.example.myagent.global.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.observability.scope")
public record ObservabilityScopeProperties(
    String region,
    String application,
    String namespaceTemplate,
    String serviceNameTemplate
) {
}
