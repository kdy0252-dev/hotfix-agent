package com.example.myagent.incident.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.GrafanaProperties;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GrafanaObservabilityAdapterTest {
    private final List<String> queryBodies = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private GrafanaObservabilityAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/ds/query", this::serveQuery);
        server.createContext("/api/alertmanager/grafana/api/v2/alerts", this::serveAlerts);
        server.start();
        adapter = new GrafanaObservabilityAdapter(
            new GrafanaProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "grafana-token",
                true,
                new GrafanaProperties.DatasourceUids("loki", "prometheus", "tempo")
            ),
            new ObservabilityScopeProperties(
                "eu",
                "app",
                "fms-eu-%s",
                "fms-eu-%s-app"
            ),
            new ObjectMapper(),
            new SensitiveEvidenceRedactor(),
            Clock.fixed(Instant.parse("2026-08-20T01:00:00Z"), ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fixesQueriesAndAlertsToTheRequestedEuAppScope() {
        var request = new AnalysisRequest.Observability(
            new AnalysisRequest.TimeRange(
                OffsetDateTime.parse("2026-08-20T12:50:00+09:00"),
                OffsetDateTime.parse("2026-08-20T13:10:00+09:00")
            ),
            AnalysisRequest.Environment.PROD,
            SourceSpec.branch("main")
        );

        var result = adapter.collect(request).get();
        String expectedStart = Long.toString(request.timeRange().startAt().toInstant().toEpochMilli());
        String expectedEnd = Long.toString(request.timeRange().endAt().toInstant().toEpochMilli());

        assertThat(queryBodies).hasSize(3);
        assertThat(queryBodies)
            .allSatisfy(body -> assertThat(body).contains(expectedStart, expectedEnd));
        assertThat(queryBodies.get(0)).contains(
            "fms-eu-prod",
            "fms-eu-prod-app",
            "maxDataPoints"
        );
        assertThat(queryBodies.get(1)).contains(
            "fms-eu-prod",
            "fms-eu-prod-app",
            "maxLines"
        );
        assertThat(result.alerts())
            .contains("fms-eu-prod-app", "[REDACTED]")
            .doesNotContain("fms-eu-prod-gateway", "plain-secret");
        assertThat(result.evidenceRefs())
            .allMatch(reference -> reference.contains("|range=")
                && reference.endsWith("|collectedAt=2026-08-20T01:00:00Z"));
    }

    private void serveQuery(HttpExchange exchange) throws IOException {
        assertAuthorization(exchange);
        queryBodies.add(new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8
        ));
        respond(exchange, "{\"results\":{\"A\":{\"frames\":[]}}}");
    }

    private void serveAlerts(HttpExchange exchange) throws IOException {
        assertAuthorization(exchange);
        respond(exchange, """
            [
              {"service_name":"fms-eu-prod-app","password":"plain-secret"},
              {"service_name":"fms-eu-prod-gateway"}
            ]
            """);
    }

    private void assertAuthorization(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer grafana-token");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
