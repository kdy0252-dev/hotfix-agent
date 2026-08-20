package com.example.myagent.incident.adapter.out.http;

import com.example.myagent.global.configuration.GrafanaProperties;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.ObservabilityEvidencePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Adapter
@Component
public class GrafanaObservabilityAdapter implements ObservabilityEvidencePort {
    private static final int MAX_RESPONSE_CHARS = 2_000_000;

    private final GrafanaProperties properties;
    private final ObservabilityScopeProperties scopeProperties;
    private final ObjectMapper objectMapper;
    private final SensitiveEvidenceRedactor redactor;
    private final GrafanaEvidenceBoundary evidenceBoundary;
    private final Clock clock;
    private final HttpClient httpClient;

    public GrafanaObservabilityAdapter(
        GrafanaProperties properties,
        ObservabilityScopeProperties scopeProperties,
        ObjectMapper objectMapper,
        SensitiveEvidenceRedactor redactor,
        Clock clock
    ) {
        this.properties = properties;
        this.scopeProperties = scopeProperties;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.evidenceBoundary = new GrafanaEvidenceBoundary(objectMapper);
        this.clock = clock;
        this.httpClient = HttpClientFactory.create(properties.tlsVerify());
    }

    @Override
    public Either<IncidentFailure, AnalysisEvidence.Observability> collect(
        AnalysisRequest.Observability request
    ) {
        return Try.of(() -> collectEvidence(request))
            .toEither()
            .mapLeft(exception -> new IncidentFailure(
                "GRAFANA_READ_FAILED",
                "Grafana 관측 증거를 수집하지 못했습니다."
            ));
    }

    private AnalysisEvidence.Observability collectEvidence(
        AnalysisRequest.Observability request
    ) throws Exception {
        validateRange(request);
        String environment = request.environment().name().toLowerCase(Locale.ROOT);
        String namespace = scopeProperties.namespaceTemplate().formatted(environment);
        String serviceName = scopeProperties.serviceNameTemplate().formatted(environment);
        String metrics = evidenceBoundary.prometheus(query(
            properties.datasourceUids().prometheus(),
            "sum(rate(http_server_requests_seconds_count{namespace=\"" + namespace
                + "\",service_name=\"" + serviceName + "\",status=~\"5..\"}[5m]))",
            "range",
            request
        ), serviceName);
        String logs = evidenceBoundary.loki(query(
            properties.datasourceUids().loki(),
            "{namespace=\"" + namespace + "\",service_name=\"" + serviceName
                + "\"} |= \"error\"",
            "range",
            request
        ), serviceName);
        String rawTraceSearch = tempoSearch(
            properties.datasourceUids().tempo(),
            serviceName,
            request
        );
        var traceSearch = evidenceBoundary.tempo(rawTraceSearch, serviceName);
        String traces = evidenceBoundary.tempoEvidence(
            traceSearch,
            traceDetails(properties.datasourceUids().tempo(), traceSearch.detailTraceIds())
        );
        String alerts = filterAlerts(
            get("/api/alertmanager/grafana/api/v2/alerts"),
            serviceName
        );
        String rangeReference = request.timeRange().startAt() + "/" + request.timeRange().endAt();
        String collectedAt = clock.instant().toString();
        return new AnalysisEvidence.Observability(
            namespace,
            serviceName,
            redactor.redact(metrics),
            redactor.redact(traces),
            redactor.redact(logs),
            redactor.redact(alerts),
            List.of(
                provenance("prometheus:http-5xx-rate", rangeReference, collectedAt),
                provenance("loki:error-logs", rangeReference, collectedAt),
                provenance("tempo:service-search", rangeReference, collectedAt),
                provenance("alerts:active", rangeReference, collectedAt)
            )
        );
    }

    private String provenance(String template, String range, String collectedAt) {
        return "grafana:" + template + "|range=" + range + "|collectedAt=" + collectedAt;
    }

    private String query(
        String datasourceUid,
        String expression,
        String queryType,
        AnalysisRequest.Observability request
    ) throws Exception {
        ObjectNode query = objectMapper.createObjectNode();
        query.put("refId", "A");
        query.put("expr", expression);
        query.put("queryType", queryType);
        query.put("maxDataPoints", 100);
        if (expression.startsWith("{")) {
            query.put("maxLines", 500);
        }
        query.set("datasource", datasource(datasourceUid));
        return postQuery(request, query);
    }

    private String tempoSearch(
        String datasourceUid,
        String serviceName,
        AnalysisRequest.Observability request
    ) throws Exception {
        ObjectNode query = objectMapper.createObjectNode();
        query.put("refId", "A");
        query.put("queryType", "search");
        query.put("serviceName", serviceName);
        query.put("limit", 20);
        query.set("datasource", datasource(datasourceUid));
        return postQuery(request, query);
    }

    private ObjectNode datasource(String uid) {
        ObjectNode datasource = objectMapper.createObjectNode();
        datasource.put("uid", uid);
        return datasource;
    }

    private String postQuery(
        AnalysisRequest.Observability request,
        ObjectNode query
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("from", Long.toString(request.timeRange().startAt().toInstant().toEpochMilli()));
        body.put("to", Long.toString(request.timeRange().endAt().toInstant().toEpochMilli()));
        body.putArray("queries").add(query);
        var httpRequest = requestBuilder("/api/ds/query")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        return requireSuccess(httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()));
    }

    private String get(String path) throws Exception {
        var request = requestBuilder(path).GET().build();
        return requireSuccess(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private List<String> traceDetails(String datasourceUid, List<String> traceIds) throws Exception {
        List<String> details = new ArrayList<>();
        for (String traceId : traceIds.stream().limit(3).toList()) {
            details.add(get(
                "/api/datasources/proxy/uid/" + encode(datasourceUid)
                    + "/api/v2/traces/" + encode(traceId)
            ));
        }
        return details;
    }

    private String filterAlerts(
        String value,
        String serviceName
    ) throws Exception {
        var alerts = objectMapper.readTree(value);
        if (!alerts.isArray()) {
            return "[]";
        }
        ArrayNode filtered = objectMapper.createArrayNode();
        alerts.forEach(alert -> {
            String serialized = alert.toString();
            if (serialized.contains(serviceName)) {
                filtered.add(alert);
            }
        });
        return objectMapper.writeValueAsString(filtered);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        URI uri = URI.create(stripTrailingSlash(properties.baseUrl().toString()) + path);
        return HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + properties.token())
            .header("Accept", "application/json");
    }

    private String requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Grafana returned HTTP " + response.statusCode());
        }
        if (response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IllegalStateException("Grafana response exceeded the configured limit");
        }
        return response.body();
    }

    private void validateRange(AnalysisRequest.Observability request) {
        var range = request.timeRange();
        if (range == null || range.startAt() == null || range.endAt() == null) {
            throw new IllegalArgumentException("startAt and endAt are required");
        }
        Duration duration = Duration.between(range.startAt(), range.endAt());
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Observation range must be between 1 second and 60 minutes");
        }
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
