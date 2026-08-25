package com.example.myagent.incident.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.GrafanaProperties;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.port.out.ObservabilityDashboardPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
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
    private final List<String> tempoSearchQueries = new CopyOnWriteArrayList<>();
    private boolean lokiHasRows;
    private boolean lokiSeverityLogs;
    private boolean tempoUnavailable;
    private String lokiLogLine;
    private TraceMode traceMode;

    private HttpServer server;
    private GrafanaObservabilityAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/ds/query", this::serveQuery);
        server.createContext("/api/alertmanager/grafana/api/v2/alerts", this::serveAlerts);
        server.createContext(
            "/api/datasources/proxy/uid/tempo/api/search",
            this::serveTempoSearch
        );
        server.createContext(
            "/api/datasources/proxy/uid/tempo/api/v2/traces/",
            this::serveTrace
        );
        server.start();
        lokiHasRows = true;
        lokiSeverityLogs = false;
        tempoUnavailable = false;
        lokiLogLine = """
            {"@timestamp":"2026-08-11T01:00:47Z","level":"ERROR",\
            "message":"request failed trace=96968fb48021bc2c093229d221efdf00",\
            "logger_name":"example.BookingService","thread_name":"http-1",\
            "stack_trace":"BookingException: invalid state\\nat example.BookingService.start"}
            """;
        traceMode = TraceMode.ERROR;
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
                "Booking API",
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

        assertThat(queryBodies).hasSize(2);
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

    @Test
    void listsActiveAlertsAndStackTracesWithGrafanaLinks() {
        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-20T12:50:00+09:00"),
            OffsetDateTime.parse("2026-08-20T13:10:00+09:00"),
            "PROD"
        )).get();

        assertThat(result).extracting(signal -> signal.type())
            .containsExactly(
                ObservabilityDashboardPort.Type.STACK_TRACE,
                ObservabilityDashboardPort.Type.ALERT
            );
        assertThat(result.get(0).reference().traceId()).isEqualTo("abcdef0123456789");
        assertThat(result.get(0).title()).isEqualTo("서비스 상태 확인 에러");
        assertThat(result.get(0).summary()).contains("오류가 감지되었습니다");
        assertThat(result.get(0).reference().technicalDetail())
            .contains("health check failed", "GET /actuator/health");
        assertThat(result.get(0).reference().linkLabel()).isEqualTo("Loki");
        assertThat(result.get(0).reference().url())
            .contains("/explore?schemaVersion=1&panes=");
        assertThat(result.get(1).reference().url())
            .contains("/alerting/list?search=HighErrorRate");
        assertThat(queryBodies).hasSize(2);
        assertThat(tempoSearchQueries).singleElement().satisfies(query ->
            assertThat(query).contains(
                "start=1787197800",
                "end=1787199000",
                "limit=20"
            ));
    }

    @Test
    void fallsBackToTempoWhenLokiHasNoMatchingTraceLog() throws Exception {
        lokiHasRows = false;

        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-20T12:50:00+09:00"),
            OffsetDateTime.parse("2026-08-20T13:10:00+09:00"),
            "PROD"
        )).get();

        assertThat(result.get(0).reference().linkLabel()).isEqualTo("Tempo");
        String decodedUrl = URLDecoder.decode(
            result.get(0).reference().url(),
            StandardCharsets.UTF_8
        );
        int paneStart = decodedUrl.indexOf("panes=") + "panes=".length();
        int paneEnd = decodedUrl.lastIndexOf("&orgId=");
        var pane = new ObjectMapper().readTree(decodedUrl.substring(paneStart, paneEnd))
            .path("ax9");
        var tempoQuery = pane.path("queries").get(0);
        assertThat(pane.path("datasource").asString()).isEqualTo("tempo");
        assertThat(tempoQuery.path("query").asString()).isEqualTo("abcdef0123456789");
        assertThat(tempoQuery.path("queryType").asString()).isEqualTo("traceql");
        assertThat(tempoQuery.path("datasource").path("uid").asString()).isEqualTo("tempo");
        assertThat(tempoQuery.path("serviceMapUseNativeHistograms").asBoolean()).isFalse();
        assertThat(tempoQuery.path("tableType").asString()).isEqualTo("traces");
        assertThat(pane.path("panelsState").path("trace").isObject()).isTrue();
        assertThat(pane.path("compact").asBoolean()).isFalse();
        assertThat(pane.path("range").path("from").asString()).isEqualTo("1787197800000");
        assertThat(pane.path("range").path("to").asString()).isEqualTo("1787199000000");
    }

    @Test
    void excludesNormalTempoTracesFromTheDashboard() {
        traceMode = TraceMode.NORMAL;

        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-20T12:50:00+09:00"),
            OffsetDateTime.parse("2026-08-20T13:10:00+09:00"),
            "PROD"
        )).get();

        assertThat(result).extracting(ObservabilityDashboardPort.Signal::type)
            .containsExactly(ObservabilityDashboardPort.Type.ALERT);
        assertThat(queryBodies).hasSize(1);
    }

    @Test
    void listsLokiWarningAndErrorLogsWhenTempoSearchIsUnavailable() {
        lokiSeverityLogs = true;
        tempoUnavailable = true;

        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-10T00:00:00+09:00"),
            OffsetDateTime.parse("2026-08-14T14:25:00+09:00"),
            "PROD"
        )).get();

        assertThat(result).anySatisfy(signal -> {
            assertThat(signal.title()).isEqualTo("Booking API 운영 로그 에러");
            assertThat(signal.occurredAt()).isEqualTo("2026-08-11T01:00:47Z");
            assertThat(signal.reference().traceId())
                .isEqualTo("96968fb48021bc2c093229d221efdf00");
            assertThat(signal.reference().linkLabel()).isEqualTo("Loki");
            assertThat(signal.reference().technicalDetail())
                .contains(
                    "발생 시각 · 2026-08-11 10:00:47 KST",
                    "레벨 · ERROR",
                    "메시지 · request failed",
                    "스택 트레이스",
                    "BookingException: invalid state"
                );
        });
        assertThat(tempoSearchQueries).isEmpty();
    }

    @Test
    void analyzesLokiEvidenceWhenTempoIsUnavailable() {
        lokiSeverityLogs = true;
        tempoUnavailable = true;
        var request = new AnalysisRequest.Observability(
            new AnalysisRequest.TimeRange(
                OffsetDateTime.parse("2026-08-11T10:40:00+09:00"),
                OffsetDateTime.parse("2026-08-11T11:00:00+09:00")
            ),
            AnalysisRequest.Environment.PROD,
            SourceSpec.branch("main")
        );

        var result = adapter.collect(request);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().logs()).contains("BookingException", "request failed");
        assertThat(result.get().traces()).contains("Tempo 증거를 수집하지 못했습니다");
    }

    @Test
    void doesNotTreatAnUnlabeledBusinessIdentifierAsATraceId() {
        lokiSeverityLogs = true;
        tempoUnavailable = true;
        lokiLogLine = """
            {"level":"ERROR","message":"booking 869541174657542081 failed"}
            """;

        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-10T00:00:00+09:00"),
            OffsetDateTime.parse("2026-08-14T14:25:00+09:00"),
            "PROD"
        )).get();

        assertThat(result).filteredOn(signal -> "Booking API 운영 로그 에러".equals(signal.title()))
            .allSatisfy(signal -> assertThat(signal.reference().traceId()).isNull());
    }

    @Test
    void includesTempoTracesWithWarningSeverity() {
        traceMode = TraceMode.WARNING;

        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-20T12:50:00+09:00"),
            OffsetDateTime.parse("2026-08-20T13:10:00+09:00"),
            "PROD"
        )).get();

        assertThat(result.get(0).type()).isEqualTo(ObservabilityDashboardPort.Type.STACK_TRACE);
        assertThat(result.get(0).title()).isEqualTo("Booking API HTTP 요청 경고");
    }

    @Test
    void rejectsObservationRangesLongerThan31DaysWithASpecificMessage() {
        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-07-01T00:00:00+09:00"),
            OffsetDateTime.parse("2026-08-02T00:00:01+09:00"),
            "PROD"
        ));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().code()).isEqualTo("INVALID_OBSERVATION_RANGE");
        assertThat(result.getLeft().message()).contains("31일 이하");
    }

    @Test
    void splitsLongTempoSearchesIntoSevenDayChunks() {
        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            OffsetDateTime.parse("2026-08-10T13:36:00+09:00"),
            OffsetDateTime.parse("2026-08-24T14:06:00+09:00"),
            "PROD"
        ));

        assertThat(result.isRight()).isTrue();
        assertThat(tempoSearchQueries).hasSize(3);
        assertThat(tempoSearchQueries.get(0)).contains(
            "start=1786943160",
            "end=1787547960"
        );
        assertThat(tempoSearchQueries.get(1)).contains(
            "start=1786338360",
            "end=1786943160"
        );
        assertThat(tempoSearchQueries.get(2)).contains(
            "start=1786336560",
            "end=1786338360"
        );
    }

    @Test
    void splitsLongLokiDashboardQueriesIntoSevenDayChunks() {
        lokiSeverityLogs = true;
        var startAt = OffsetDateTime.parse("2026-08-03T09:42:00+09:00");
        var endAt = OffsetDateTime.parse("2026-08-25T10:14:00+09:00");

        var result = adapter.findSignals(new ObservabilityDashboardPort.SignalQuery(
            startAt,
            endAt,
            "PROD"
        ));

        assertThat(result.isRight()).isTrue();
        assertThat(queryBodies).hasSize(4);
        assertThat(queryBodies).anyMatch(body -> body.contains(
            Long.toString(startAt.toInstant().toEpochMilli())
        ));
        assertThat(queryBodies).anyMatch(body -> body.contains(
            Long.toString(endAt.toInstant().toEpochMilli())
        ));
        assertThat(result.get())
            .filteredOn(signal -> "Booking API 운영 로그 에러".equals(signal.title()))
            .hasSize(1);
    }

    private void serveQuery(HttpExchange exchange) throws IOException {
        assertAuthorization(exchange);
        queryBodies.add(new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8
        ));
        if (lokiSeverityLogs) {
            String encodedLogLine = new ObjectMapper().writeValueAsString(lokiLogLine);
            respond(exchange, """
                {"results":{"A":{"frames":[{
                  "schema":{"fields":[
                    {"name":"labels"},{"name":"Time"},{"name":"Line"}
                  ]},
                  "data":{"values":[
                    [{"detected_level":"error"}],
                    [1786410047000],
                    [%s]
                  ]}
                }]}}}
                """.formatted(encodedLogLine));
            return;
        }
        if (!lokiHasRows) {
            respond(exchange, "{\"results\":{\"A\":{\"frames\":[]}}}");
            return;
        }
        respond(exchange, """
            {"results":{"A":{"frames":[{
              "schema":{"fields":[{"name":"traceID"},{"name":"service_name"}]},
              "data":{"values":[["abcdef0123456789"],["fms-eu-prod-app"]]}
            }]}}}
            """);
    }

    private void serveAlerts(HttpExchange exchange) throws IOException {
        assertAuthorization(exchange);
        respond(exchange, """
            [
              {"service_name":"fms-eu-prod-app","password":"plain-secret",
                "startsAt":"2026-08-20T03:55:00Z",
                "labels":{"alertname":"HighErrorRate"},
                "annotations":{"description":"5xx rate exceeded"}},
              {"service_name":"fms-eu-prod-gateway"}
            ]
            """);
    }

    private void serveTempoSearch(HttpExchange exchange) throws IOException {
        assertAuthorization(exchange);
        if (tempoUnavailable) {
            respond(exchange, 502, "");
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        tempoSearchQueries.add(query);
        assertThat(query).contains("tags=service.name%3Dfms-eu-prod-app");
        respond(exchange, """
            {"traces":[
              {"traceID":"abcdef0123456789","rootServiceName":"fms-eu-prod-app"},
              {"traceID":"0123456789abcdef","rootServiceName":"fms-eu-prod-gateway"}
            ]}
            """);
    }

    private void serveTrace(HttpExchange exchange) throws IOException {
        assertAuthorization(exchange);
        if (traceMode == TraceMode.NORMAL) {
            respond(exchange, """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{
                  "name":"GET /actuator/health",
                  "status":{"code":"STATUS_CODE_OK"},
                  "attributes":[]
                }]}]}]}
                """);
            return;
        }
        if (traceMode == TraceMode.WARNING) {
            respond(exchange, """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{
                  "name":"POST /vehicles",
                  "status":{"code":"STATUS_CODE_UNSET"},
                  "attributes":[{"key":"log.level","value":{"stringValue":"WARN"}}]
                }]}]}]}
                """);
            return;
        }
        respond(exchange, """
            {"resourceSpans":[{"scopeSpans":[{"spans":[{
              "name":"GET /actuator/health",
              "status":{"code":"STATUS_CODE_ERROR"},
              "attributes":[{"key":"exception.message","value":{
                "stringValue":"health check failed"
              }}]
            }]}]}]}
            """);
    }

    private enum TraceMode {
        NORMAL,
        WARNING,
        ERROR
    }

    private void assertAuthorization(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer grafana-token");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
