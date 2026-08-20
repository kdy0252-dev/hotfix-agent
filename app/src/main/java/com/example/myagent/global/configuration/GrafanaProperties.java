package com.example.myagent.global.configuration;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.integrations.grafana")
public record GrafanaProperties(
    URI baseUrl,
    String token,
    boolean tlsVerify,
    DatasourceUids datasourceUids
) {
    public record DatasourceUids(
        String loki,
        String prometheus,
        String tempo
    ) {
    }
}
