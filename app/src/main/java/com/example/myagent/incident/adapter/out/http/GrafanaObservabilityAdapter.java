package com.example.myagent.incident.adapter.out.http;

import com.example.myagent.global.configuration.GrafanaProperties;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.ObservabilityDashboardPort;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Adapter
@Component
public class GrafanaObservabilityAdapter implements
    ObservabilityEvidencePort, ObservabilityDashboardPort {
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final Duration MAX_OBSERVATION_RANGE = Duration.ofDays(31);
    private static final Duration MAX_LOKI_QUERY_RANGE = Duration.ofDays(7);
    private static final Duration MAX_TEMPO_SEARCH_RANGE = Duration.ofDays(7);
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile(
        "(?i)trace(?:[_-]?id)?[\\s\\\"']*[:=][\\s\\\"']*"
            + "([0-9a-f]{16}|[0-9a-f]{32})(?![0-9a-f])"
    );

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

    @Override
    public Either<IncidentFailure, List<Signal>> findSignals(SignalQuery query) {
        return Try.of(() -> signals(query))
            .toEither()
            .mapLeft(this::dashboardFailure);
    }

    private IncidentFailure dashboardFailure(Throwable exception) {
        return exception instanceof IllegalArgumentException
            ? new IncidentFailure("INVALID_OBSERVATION_RANGE", exception.getMessage())
            : new IncidentFailure(
                "GRAFANA_DASHBOARD_READ_FAILED",
                "Grafana 알람과 trace를 조회하지 못했습니다."
            );
    }

    private AnalysisEvidence.Observability collectEvidence(
        AnalysisRequest.Observability request
    ) throws Exception {
        validateRange(request.timeRange());
        String environment = request.environment().name().toLowerCase(Locale.ROOT);
        String namespace = scopeProperties.namespaceTemplate().formatted(environment);
        String serviceName = scopeProperties.serviceNameTemplate().formatted(environment);
        String metrics = Try.of(() -> evidenceBoundary.prometheus(query(
            properties.datasourceUids().prometheus(),
            "sum(rate(http_server_requests_seconds_count{namespace=\"" + namespace
                + "\",service_name=\"" + serviceName + "\",status=~\"5..\"}[5m]))",
            "range",
            request.timeRange()
        ), serviceName)).getOrElse("Prometheus 증거를 수집하지 못했습니다.");
        String logs = evidenceBoundary.loki(query(
            properties.datasourceUids().loki(),
            "{namespace=\"" + namespace + "\",service_name=\"" + serviceName
                + "\"} |= \"error\"",
            "range",
            request.timeRange()
        ), serviceName);
        String traces = Try.of(() -> {
            String rawTraceSearch = tempoSearch(
                properties.datasourceUids().tempo(),
                serviceName,
                request.timeRange()
            );
            var traceSearch = evidenceBoundary.tempo(rawTraceSearch, serviceName);
            return evidenceBoundary.tempoEvidence(
                traceSearch,
                traceDetails(
                    properties.datasourceUids().tempo(),
                    traceSearch.detailTraceIds(),
                    3
                )
            );
        }).getOrElse("Tempo 증거를 수집하지 못했습니다.");
        String alerts = Try.of(() -> filterAlerts(
            get("/api/alertmanager/grafana/api/v2/alerts"),
            serviceName
        )).getOrElse("Grafana 알람을 수집하지 못했습니다.");
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

    private List<Signal> signals(SignalQuery query) throws Exception {
        AnalysisRequest.Environment environment = AnalysisRequest.Environment.valueOf(
            query.environment().toUpperCase(Locale.ROOT)
        );
        var timeRange = new AnalysisRequest.TimeRange(query.startAt(), query.endAt());
        validateRange(timeRange);
        String environmentName = environment.name().toLowerCase(Locale.ROOT);
        String namespace = scopeProperties.namespaceTemplate().formatted(environmentName);
        String serviceName = scopeProperties.serviceNameTemplate().formatted(environmentName);
        List<Signal> logSignals = lokiSeveritySignals(
            query,
            timeRange,
            namespace,
            serviceName
        );
        List<String> traceIds = logSignals.isEmpty()
            ? Try.of(() -> tempoSearch(
                properties.datasourceUids().tempo(),
                serviceName,
                timeRange
            )).map(rawTraceSearch -> evidenceBoundary.tempo(rawTraceSearch, serviceName))
                .map(GrafanaEvidenceBoundary.TempoSearch::detailTraceIds)
                .getOrElse(List.of())
            : List.of();
        List<SignificantTrace> significantTraces = significantTraces(
            traceIds,
            traceDetails(properties.datasourceUids().tempo(), traceIds, 20)
        );
        Set<String> loggedTraceIds = logSignals.stream()
            .map(Signal::reference)
            .map(Reference::traceId)
            .filter(traceId -> traceId != null && !traceId.isBlank())
            .collect(Collectors.toSet());
        significantTraces = significantTraces.stream()
            .filter(trace -> !loggedTraceIds.contains(trace.traceId()))
            .toList();
        List<String> significantTraceIds = significantTraces.stream()
            .map(SignificantTrace::traceId)
            .toList();
        List<Signal> signals = new ArrayList<>();
        signals.addAll(Try.of(() -> alertSignals(
            filterAlerts(get("/api/alertmanager/grafana/api/v2/alerts"), serviceName)
        )).getOrElse(List.of()));
        signals.addAll(logSignals);
        signals.addAll(traceSignals(
            significantTraces,
            lokiTraceSearches(significantTraceIds, timeRange, environmentName),
            query
        ));
        return signals.stream()
            .sorted(Comparator.comparing(Signal::occurredAt).reversed())
            .toList();
    }

    private List<Signal> lokiSeveritySignals(
        SignalQuery signalQuery,
        AnalysisRequest.TimeRange timeRange,
        String namespace,
        String serviceName
    ) throws Exception {
        List<Signal> signals = new ArrayList<>();
        for (AnalysisRequest.TimeRange chunk : splitRanges(timeRange, MAX_LOKI_QUERY_RANGE)) {
            String response = query(
                properties.datasourceUids().loki(),
                "{namespace=\"" + namespace + "\",service_name=\"" + serviceName
                    + "\"} |~ \"(?i)(warn|error|fatal|critical)\"",
                "range",
                chunk
            );
            signals.addAll(lokiLogSignals(response, signalQuery, namespace));
        }
        return uniqueRecentSignals(signals);
    }

    private List<Signal> uniqueRecentSignals(List<Signal> signals) {
        return signals.stream()
            .sorted(Comparator.comparing(Signal::occurredAt).reversed())
            .filter(new Predicate<>() {
                private final Set<String> identities = new LinkedHashSet<>();

                @Override
                public boolean test(Signal signal) {
                    String traceId = signal.reference().traceId();
                    String identity = traceId == null || traceId.isBlank()
                        ? signal.occurredAt() + "|" + signal.reference().technicalDetail()
                        : traceId;
                    return identities.add(identity);
                }
            })
            .limit(20)
            .toList();
    }

    private List<Signal> lokiLogSignals(
        String response,
        SignalQuery query,
        String namespace
    ) throws Exception {
        JsonNode frames = objectMapper.readTree(response)
            .path("results").path("A").path("frames");
        if (!frames.isArray()) {
            return List.of();
        }
        return uniqueRecentSignals(StreamSupport.stream(frames.spliterator(), false)
            .flatMap(frame -> lokiFrameSignals(frame, query, namespace).stream())
            .toList());
    }

    private List<Signal> lokiFrameSignals(
        JsonNode frame,
        SignalQuery query,
        String namespace
    ) {
        List<String> fieldNames = StreamSupport.stream(
            frame.path("schema").path("fields").spliterator(),
            false
        ).map(field -> field.path("name").asString()).toList();
        int labelsIndex = fieldNames.indexOf("labels");
        int timeIndex = fieldNames.indexOf("Time");
        int lineIndex = fieldNames.indexOf("Line");
        JsonNode values = frame.path("data").path("values");
        if (labelsIndex < 0 || timeIndex < 0 || lineIndex < 0 || !values.isArray()) {
            return List.of();
        }
        int rowCount = values.path(timeIndex).size();
        return IntStream.range(0, rowCount)
            .mapToObj(row -> lokiLogSignal(
                values.path(labelsIndex).path(row),
                values.path(timeIndex).path(row),
                values.path(lineIndex).path(row),
                query,
                namespace
            ))
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<Signal> lokiLogSignal(
        JsonNode labels,
        JsonNode time,
        JsonNode line,
        SignalQuery query,
        String namespace
    ) {
        String message = line.asString();
        String severity = firstNonBlank(
            labels.path("detected_level").asString(),
            labels.path("level").asString(),
            ""
        ).toUpperCase(Locale.ROOT);
        if (!isErrorSeverity(severity) && !isWarningSeverity(severity)) {
            return Optional.empty();
        }
        String traceId = traceId(labels, message).orElse(null);
        Instant occurredAt = Try.of(() -> Instant.ofEpochMilli(time.asLong()))
            .getOrElse(query.endAt().toInstant());
        boolean error = isErrorSeverity(severity);
        String title = error ? "EU 앱 운영 로그 에러" : "EU 앱 운영 로그 경고";
        String summary = error
            ? "운영 로그에서 오류를 감지했습니다. Details에서 원문과 Trace ID를 확인하세요."
            : "운영 로그에서 경고를 감지했습니다. Details에서 원문과 Trace ID를 확인하세요.";
        return Optional.of(new Signal(
            Type.STACK_TRACE,
            title,
            summary,
            occurredAt,
            new Reference(
                traceId,
                redactor.redact(formatLogDetail(message)),
                "Loki",
                traceId == null
                    ? lokiSeverityExploreUrl(namespace, query)
                    : lokiExploreUrl(traceId, query)
            )
        ));
    }

    private Optional<String> traceId(JsonNode labels, String message) {
        String labeledTraceId = firstNonBlank(
            labels.path("trace_id").asString(),
            firstNonBlank(
                labels.path("traceID").asString(),
                labels.path("traceId").asString(),
                ""
            ),
            ""
        );
        if (labeledTraceId.matches("(?i)([0-9a-f]{16}|[0-9a-f]{32})")) {
            return Optional.of(labeledTraceId);
        }
        var matcher = TRACE_ID_PATTERN.matcher(message);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String formatLogDetail(String message) {
        return Try.of(() -> objectMapper.readTree(message))
            .filter(JsonNode::isObject)
            .map(this::formatLogObject)
            .getOrElse(message);
    }

    private String formatLogObject(JsonNode log) {
        String fields = List.of(
            new LogField("발생 시각", log.path("@timestamp").asString()),
            new LogField("레벨", log.path("level").asString()),
            new LogField("메시지", log.path("message").asString()),
            new LogField("로거", log.path("logger_name").asString()),
            new LogField("스레드", log.path("thread_name").asString())
        ).stream()
            .filter(LogField::present)
            .map(LogField::line)
            .collect(Collectors.joining("\n"));
        String stackTrace = log.path("stack_trace").asString();
        return stackTrace.isBlank()
            ? fields
            : fields + "\n\n스택 트레이스\n" + stackTrace;
    }

    private List<Signal> alertSignals(String value) throws Exception {
        JsonNode alerts = objectMapper.readTree(value);
        if (!alerts.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(alerts.spliterator(), false)
            .map(this::alertSignal)
            .toList();
    }

    private Signal alertSignal(JsonNode alert) {
        String title = firstNonBlank(
            alert.path("labels").path("alertname").asString(),
            alert.path("annotations").path("summary").asString(),
            "Active alert"
        );
        String summary = firstNonBlank(
            alert.path("annotations").path("description").asString(),
            alert.path("annotations").path("summary").asString(),
            "Grafana에서 활성 알람의 상세 정보를 확인하세요."
        );
        Instant occurredAt = Try.of(() -> Instant.parse(alert.path("startsAt").asString()))
            .getOrElse(clock.instant());
        return new Signal(
            Type.ALERT,
            redactor.redact(title),
            redactor.redact(summary),
            occurredAt,
            new Reference(
                null,
                redactor.redact(summary),
                "Grafana",
                grafanaUrl("/alerting/list?search=" + encode(title))
            )
        );
    }

    private List<Signal> traceSignals(
        List<SignificantTrace> significantTraces,
        List<String> lokiSearches,
        SignalQuery query
    ) {
        return IntStream.range(0, significantTraces.size()).mapToObj(index -> {
            SignificantTrace trace = significantTraces.get(index);
            boolean lokiAvailable = index < lokiSearches.size()
                && hasLokiRows(lokiSearches.get(index));
            return new Signal(
                Type.STACK_TRACE,
                trace.description().title(),
                trace.description().summary(),
                query.endAt().toInstant(),
                new Reference(
                    trace.traceId(),
                    trace.description().technicalDetail(),
                    lokiAvailable ? "Loki" : "Tempo",
                    lokiAvailable
                        ? lokiExploreUrl(trace.traceId(), query)
                        : tempoExploreUrl(trace.traceId(), query)
                )
            );
        }).toList();
    }

    private List<SignificantTrace> significantTraces(
        List<String> traceIds,
        List<String> traceDetails
    ) {
        return IntStream.range(0, traceIds.size())
            .mapToObj(index -> {
                String traceId = traceIds.get(index);
                String detail = index < traceDetails.size() ? traceDetails.get(index) : "{}";
                return traceDescription(detail, traceId)
                    .map(description -> new SignificantTrace(traceId, description));
            })
            .flatMap(Optional::stream)
            .toList();
    }

    private List<String> lokiTraceSearches(
        List<String> traceIds,
        AnalysisRequest.TimeRange timeRange,
        String environment
    ) {
        String namespace = scopeProperties.namespaceTemplate().formatted(environment);
        return traceIds.stream().map(traceId -> Try.of(() -> query(
            properties.datasourceUids().loki(),
            "{namespace=\"" + namespace + "\"} |= \"" + traceId + "\"",
            "range",
            timeRange
        )).getOrElse("{}"))
            .toList();
    }

    private boolean hasLokiRows(String response) {
        return Try.of(() -> objectMapper.readTree(response))
            .map(root -> root.path("results").path("A").path("frames"))
            .map(frames -> StreamSupport.stream(frames.spliterator(), false)
                .map(frame -> frame.path("data").path("values"))
                .flatMap(values -> StreamSupport.stream(values.spliterator(), false))
                .anyMatch(value -> value.isArray() && !value.isEmpty()))
            .getOrElse(false);
    }

    private Optional<TraceDescription> traceDescription(String response, String traceId) {
        return Try.of(() -> objectMapper.readTree(response))
            .map(root -> StreamSupport.stream(root.findValues("spans").spliterator(), false)
                .flatMap(spans -> StreamSupport.stream(spans.spliterator(), false))
                .filter(JsonNode::isObject)
                .toList())
            .map(spans -> spans.stream().filter(this::errorSpan).findFirst()
                .map(span -> describeSpan(span, traceId, TraceSeverity.ERROR))
                .or(() -> spans.stream().filter(this::warningSpan).findFirst()
                    .map(span -> describeSpan(span, traceId, TraceSeverity.WARNING))))
            .getOrElse(Optional.empty());
    }

    private TraceDescription describeSpan(
        JsonNode span,
        String traceId,
        TraceSeverity severity
    ) {
        String spanName = firstNonBlank(span.path("name").asString(), null, "이름 없는 span");
        String exceptionMessage = StreamSupport.stream(
            span.path("attributes").spliterator(),
            false
        )
            .filter(attribute -> "exception.message".equals(attribute.path("key").asString())
                || "error.message".equals(attribute.path("key").asString()))
            .map(attribute -> attribute.path("value").path("stringValue").asString())
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
        String technicalDetail = firstNonBlank(
            exceptionMessage,
            span.path("status").path("message").asString(),
            "원본 span · " + spanName
        );
        String friendlyName = friendlySpanName(spanName);
        String summary = severity == TraceSeverity.ERROR
            ? friendlyName + " 처리 중 오류가 감지되었습니다. Details에서 기술 정보를 확인하세요."
            : friendlyName + " 처리 중 경고가 감지되었습니다. Details에서 기술 정보를 확인하세요.";
        return new TraceDescription(
            redactor.redact(friendlyName + severity.titleSuffix()),
            redactor.redact(summary),
            redactor.redact(technicalDetail + "\n원본 span · " + spanName
                + "\nTrace ID · " + traceId)
        );
    }

    private String friendlySpanName(String spanName) {
        String normalized = spanName.toLowerCase(Locale.ROOT);
        if (normalized.contains("/actuator/health")) {
            return "서비스 상태 확인";
        }
        if (normalized.contains("/actuator/prometheus")) {
            return "모니터링 지표 수집";
        }
        boolean httpRequest = normalized.startsWith("http ")
            || normalized.matches("(get|post|put|patch|delete) /.*");
        return httpRequest ? "EU 앱 HTTP 요청" : "EU 앱 요청 Trace";
    }

    private boolean errorSpan(JsonNode span) {
        String statusCode = span.path("status").path("code").asString();
        boolean errorStatus = "2".equals(statusCode)
            || "STATUS_CODE_ERROR".equalsIgnoreCase(statusCode);
        boolean hasErrorAttribute = StreamSupport.stream(
            span.path("attributes").spliterator(),
            false
        ).map(attribute -> attribute.path("key").asString().toLowerCase(Locale.ROOT))
            .anyMatch(key -> key.startsWith("exception.") || key.startsWith("error."));
        boolean hasExceptionEvent = StreamSupport.stream(
            span.path("events").spliterator(),
            false
        ).anyMatch(event -> "exception".equalsIgnoreCase(event.path("name").asString()));
        int httpStatus = httpStatusCode(span);
        return errorStatus || hasErrorAttribute || hasExceptionEvent || httpStatus >= 500
            || isErrorSeverity(severityValue(span));
    }

    private boolean warningSpan(JsonNode span) {
        int httpStatus = httpStatusCode(span);
        return httpStatus >= 400 || isWarningSeverity(severityValue(span));
    }

    private int httpStatusCode(JsonNode span) {
        return StreamSupport.stream(span.path("attributes").spliterator(), false)
            .filter(attribute -> {
                String key = attribute.path("key").asString();
                return "http.status_code".equals(key)
                    || "http.response.status_code".equals(key);
            })
            .map(this::attributeValue)
            .map(value -> Try.of(() -> Integer.parseInt(value)).getOrElse(0))
            .findFirst()
            .orElse(0);
    }

    private String severityValue(JsonNode span) {
        return StreamSupport.stream(span.path("attributes").spliterator(), false)
            .filter(attribute -> {
                String key = attribute.path("key").asString().toLowerCase(Locale.ROOT);
                return "severity".equals(key)
                    || "severity_text".equals(key)
                    || "level".equals(key)
                    || "log.level".equals(key)
                    || "log.severity".equals(key);
            })
            .map(this::attributeValue)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .findFirst()
            .orElse("");
    }

    private String attributeValue(JsonNode attribute) {
        JsonNode value = attribute.path("value");
        return firstNonBlank(
            value.path("stringValue").asString(),
            value.path("intValue").asString(),
            ""
        );
    }

    private boolean isErrorSeverity(String severity) {
        return "ERROR".equals(severity)
            || "FATAL".equals(severity)
            || "CRITICAL".equals(severity);
    }

    private boolean isWarningSeverity(String severity) {
        return "WARN".equals(severity) || "WARNING".equals(severity);
    }

    private String lokiExploreUrl(String traceId, SignalQuery query) {
        String environment = query.environment().toLowerCase(Locale.ROOT);
        String namespace = scopeProperties.namespaceTemplate().formatted(environment);
        String pane = """
            {"loki":{"datasource":"%s","queries":[{"refId":"A","expr":"{namespace=\\\"%s\\\"} |= \\\"%s\\\"","queryType":"range"}],"range":{"from":"%s","to":"%s"}}}
            """.formatted(
                properties.datasourceUids().loki(),
                namespace,
                traceId,
                query.startAt().toInstant(),
                query.endAt().toInstant()
            ).trim();
        return grafanaUrl("/explore?schemaVersion=1&panes=" + encode(pane) + "&orgId=1");
    }

    private String lokiSeverityExploreUrl(String namespace, SignalQuery query) {
        String expression = "{namespace=\"" + namespace
            + "\"} |~ \"(?i)(warn|error|fatal|critical)\"";
        String pane = """
            {"loki":{"datasource":"%s","queries":[{"refId":"A","expr":"%s","queryType":"range"}],"range":{"from":"%s","to":"%s"}}}
            """.formatted(
                properties.datasourceUids().loki(),
                expression.replace("\"", "\\\""),
                query.startAt().toInstant(),
                query.endAt().toInstant()
            ).trim();
        return grafanaUrl("/explore?schemaVersion=1&panes=" + encode(pane) + "&orgId=1");
    }

    private String tempoExploreUrl(String traceId, SignalQuery query) {
        String pane = """
            {
              "ax9": {
                "datasource": "%s",
                "queries": [{
                  "refId": "A",
                  "query": "%s",
                  "queryType": "traceql",
                  "datasource": {"type": "tempo", "uid": "%s"},
                  "serviceMapUseNativeHistograms": false,
                  "limit": 20,
                  "tableType": "traces",
                  "metricsQueryType": "range",
                  "filters": [{"id": "trace-id", "operator": "=", "scope": "span"}]
                }],
                "range": {"from": "%s", "to": "%s"},
                "panelsState": {
                  "trace": {
                    "spanFilters": {
                      "spanNameOperator": "=",
                      "serviceNameOperator": "=",
                      "fromOperator": ">",
                      "toOperator": "<",
                      "tags": [{"id": "trace-tag", "operator": "="}]
                    }
                  }
                },
                "compact": false
              }
            }
            """.formatted(
                properties.datasourceUids().tempo(),
                traceId,
                properties.datasourceUids().tempo(),
                query.startAt().toInstant().toEpochMilli(),
                query.endAt().toInstant().toEpochMilli()
            ).trim();
        return grafanaUrl("/explore?schemaVersion=1&panes=" + encode(pane) + "&orgId=1");
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : fallback;
    }

    private String grafanaUrl(String path) {
        return stripTrailingSlash(properties.baseUrl().toString()) + path;
    }

    private String provenance(String template, String range, String collectedAt) {
        return "grafana:" + template + "|range=" + range + "|collectedAt=" + collectedAt;
    }

    private record SignificantTrace(String traceId, TraceDescription description) {
    }

    private record TraceDescription(String title, String summary, String technicalDetail) {
    }

    private record LogField(String label, String value) {
        private boolean present() {
            return value != null && !value.isBlank();
        }

        private String line() {
            return label + " · " + value;
        }
    }

    private enum TraceSeverity {
        WARNING(" 경고"),
        ERROR(" 에러");

        private final String titleSuffix;

        TraceSeverity(String titleSuffix) {
            this.titleSuffix = titleSuffix;
        }

        private String titleSuffix() {
            return titleSuffix;
        }
    }

    private String query(
        String datasourceUid,
        String expression,
        String queryType,
        AnalysisRequest.TimeRange timeRange
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
        return postQuery(timeRange, query);
    }

    private String tempoSearch(
        String datasourceUid,
        String serviceName,
        AnalysisRequest.TimeRange timeRange
    ) throws Exception {
        List<String> responses = new ArrayList<>();
        for (AnalysisRequest.TimeRange chunk : tempoSearchRanges(timeRange)) {
            responses.add(tempoSearchChunk(datasourceUid, serviceName, chunk));
        }
        return mergeTempoSearchResponses(responses);
    }

    private String tempoSearchChunk(
        String datasourceUid,
        String serviceName,
        AnalysisRequest.TimeRange timeRange
    ) throws Exception {
        String tags = encode("service.name=" + serviceName);
        long start = timeRange.startAt().toInstant().getEpochSecond();
        long end = timeRange.endAt().toInstant().getEpochSecond();
        return get(
            "/api/datasources/proxy/uid/" + encode(datasourceUid)
                + "/api/search?tags=" + tags
                + "&start=" + start
                + "&end=" + end
                + "&limit=20"
        );
    }

    private List<AnalysisRequest.TimeRange> tempoSearchRanges(
        AnalysisRequest.TimeRange requestedRange
    ) {
        return splitRanges(requestedRange, MAX_TEMPO_SEARCH_RANGE);
    }

    private List<AnalysisRequest.TimeRange> splitRanges(
        AnalysisRequest.TimeRange requestedRange,
        Duration maximumRange
    ) {
        List<AnalysisRequest.TimeRange> ranges = new ArrayList<>();
        var chunkEnd = requestedRange.endAt();
        while (chunkEnd.isAfter(requestedRange.startAt())) {
            var candidateStart = chunkEnd.minus(maximumRange);
            var chunkStart = candidateStart.isBefore(requestedRange.startAt())
                ? requestedRange.startAt() : candidateStart;
            ranges.add(new AnalysisRequest.TimeRange(chunkStart, chunkEnd));
            chunkEnd = chunkStart;
        }
        return ranges;
    }

    private String mergeTempoSearchResponses(List<String> responses) throws Exception {
        ObjectNode merged = objectMapper.createObjectNode();
        ArrayNode mergedTraces = merged.putArray("traces");
        var traceIds = new LinkedHashSet<String>();
        for (String response : responses) {
            JsonNode traces = objectMapper.readTree(response).path("traces");
            if (!traces.isArray()) {
                continue;
            }
            for (JsonNode trace : traces) {
                String traceId = trace.path("traceID").asString();
                if (!traceId.isBlank() && traceIds.add(traceId)) {
                    mergedTraces.add(trace);
                }
            }
        }
        return objectMapper.writeValueAsString(merged);
    }

    private ObjectNode datasource(String uid) {
        ObjectNode datasource = objectMapper.createObjectNode();
        datasource.put("uid", uid);
        return datasource;
    }

    private String postQuery(
        AnalysisRequest.TimeRange timeRange,
        ObjectNode query
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("from", Long.toString(timeRange.startAt().toInstant().toEpochMilli()));
        body.put("to", Long.toString(timeRange.endAt().toInstant().toEpochMilli()));
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

    private List<String> traceDetails(
        String datasourceUid,
        List<String> traceIds,
        int limit
    ) throws Exception {
        List<String> details = new ArrayList<>();
        for (String traceId : traceIds.stream().limit(limit).toList()) {
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

    private void validateRange(AnalysisRequest.TimeRange range) {
        if (range == null || range.startAt() == null || range.endAt() == null) {
            throw new IllegalArgumentException("startAt and endAt are required");
        }
        Duration duration = Duration.between(range.startAt(), range.endAt());
        if (duration.isNegative() || duration.isZero()
            || duration.compareTo(MAX_OBSERVATION_RANGE) > 0) {
            throw new IllegalArgumentException("관측 범위는 1초 이상 31일 이하여야 합니다.");
        }
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
