package com.example.myagent.incident.adapter.out.http;

import io.vavr.control.Try;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class GrafanaEvidenceBoundary {
    private static final int MAXIMUM_RESPONSE_CHARACTERS = 2_000_000;

    private final ObjectMapper objectMapper;

    GrafanaEvidenceBoundary(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String prometheus(String response, String serviceName) {
        return boundedFrames(response, serviceName, 100, null);
    }

    String loki(String response, String serviceName) {
        return boundedFrames(response, serviceName, Integer.MAX_VALUE, 500);
    }

    TempoSearch tempo(String response, String serviceName) {
        JsonNode root = parse(response);
        if (root.path("traces").isArray()) {
            return nativeTempo(root, serviceName);
        }
        String bounded = boundedFrames(response, serviceName, 20, 20);
        return new TempoSearch(bounded, traceIds(bounded).stream().limit(20).toList());
    }

    private TempoSearch nativeTempo(JsonNode root, String serviceName) {
        ArrayNode traces = array(root.path("traces"));
        List<JsonNode> accepted = new ArrayList<>();
        for (JsonNode trace : traces) {
            if (accepted.size() >= 20) {
                break;
            }
            if (serviceName.equals(trace.path("rootServiceName").asString())) {
                accepted.add(trace);
            }
        }
        traces.removeAll();
        accepted.forEach(traces::add);
        String bounded = serialize(root);
        requireSize(bounded);
        List<String> ids = accepted.stream()
            .map(trace -> trace.path("traceID").asString())
            .filter(traceId -> traceId.matches("(?i)[0-9a-f]{16,32}"))
            .distinct()
            .limit(20)
            .toList();
        return new TempoSearch(bounded, ids);
    }

    String details(List<String> responses) {
        ArrayNode details = objectMapper.createArrayNode();
        responses.stream().limit(3).map(this::parse).forEach(details::add);
        return serialize(details);
    }

    String tempoEvidence(TempoSearch search, List<String> detailResponses) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.set("search", parse(search.search()));
        evidence.set("details", parse(details(detailResponses)));
        return serialize(evidence);
    }

    private String boundedFrames(
        String response,
        String serviceName,
        int maximumFrames,
        Integer maximumRows
    ) {
        requireSize(response);
        JsonNode root = parse(response);
        ArrayNode frames = frames(root);
        List<JsonNode> accepted = new ArrayList<>();
        for (JsonNode frame : frames) {
            if (accepted.size() >= maximumFrames) {
                break;
            }
            if (belongsToService(frame, serviceName)) {
                accepted.add(frame);
            }
        }
        frames.removeAll();
        accepted.forEach(frames::add);
        if (maximumRows != null) {
            trimRows(frames, maximumRows);
        }
        String bounded = serialize(root);
        requireSize(bounded);
        return bounded;
    }

    private void trimRows(ArrayNode frames, int maximumRows) {
        int remaining = maximumRows;
        for (JsonNode frame : frames) {
            ArrayNode values = array(frame.path("data").path("values"));
            int rows = values.isEmpty() ? 0 : values.get(0).size();
            int acceptedRows = Math.min(rows, Math.max(remaining, 0));
            for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                ArrayNode column = array(values.get(columnIndex));
                ArrayNode limitedColumn = objectMapper.createArrayNode();
                for (int rowIndex = 0; rowIndex < Math.min(acceptedRows, column.size()); rowIndex++) {
                    limitedColumn.add(column.get(rowIndex));
                }
                values.set(columnIndex, limitedColumn);
            }
            remaining -= acceptedRows;
        }
    }

    private List<String> traceIds(String response) {
        List<String> ids = new ArrayList<>();
        for (JsonNode frame : frames(parse(response))) {
            ArrayNode fields = array(frame.path("schema").path("fields"));
            ArrayNode values = array(frame.path("data").path("values"));
            for (int fieldIndex = 0; fieldIndex < fields.size() && fieldIndex < values.size();
                 fieldIndex++) {
                if (isTraceIdField(fields.get(fieldIndex).path("name").asString())) {
                    values.get(fieldIndex).forEach(value -> addTraceId(ids, value.asString()));
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private void addTraceId(List<String> ids, String value) {
        if (value != null && value.matches("(?i)[0-9a-f]{16,32}")) {
            ids.add(value);
        }
    }

    private boolean isTraceIdField(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("_", "");
        return "traceid".equals(normalized);
    }

    private boolean belongsToService(JsonNode frame, String serviceName) {
        String serialized = frame.toString();
        boolean declaresService = serialized.contains("service_name")
            || serialized.contains("service.name")
            || serialized.contains("serviceName");
        return !declaresService || serialized.contains(serviceName);
    }

    private ArrayNode frames(JsonNode root) {
        JsonNode node = root.path("results").path("A").path("frames");
        if (!node.isArray()) {
            throw new IllegalArgumentException("Grafana response has no results.A.frames array");
        }
        return (ArrayNode) node;
    }

    private ArrayNode array(JsonNode node) {
        return node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
    }

    private JsonNode parse(String response) {
        return Try.of(() -> objectMapper.readTree(response))
            .getOrElseThrow(exception -> new IllegalArgumentException(
                "Invalid Grafana JSON response",
                exception
            ));
    }

    private String serialize(JsonNode node) {
        return Try.of(() -> objectMapper.writeValueAsString(node))
            .getOrElseThrow(exception -> new IllegalStateException(
                "Unable to serialize bounded Grafana evidence",
                exception
            ));
    }

    private void requireSize(String response) {
        if (response == null || response.length() > MAXIMUM_RESPONSE_CHARACTERS) {
            throw new IllegalArgumentException("Grafana response exceeds 2 MB boundary");
        }
    }

    record TempoSearch(String search, List<String> detailTraceIds) {
        TempoSearch {
            detailTraceIds = List.copyOf(detailTraceIds);
        }
    }
}
