package com.example.myagent.incident.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class GrafanaEvidenceBoundaryTest {
    private static final String EU_APP = "fms-eu-prod-app";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GrafanaEvidenceBoundary boundary = new GrafanaEvidenceBoundary(objectMapper);

    @Test
    void limitsPrometheusToOneHundredEuAppSeries() {
        ObjectNode response = response();
        ArrayNode frames = frames(response);
        for (int index = 0; index < 105; index++) {
            frames.add(frame(index == 0 ? "fms-eu-prod-gateway" : EU_APP, "Value", 1));
        }

        JsonNode bounded = parse(boundary.prometheus(response.toString(), EU_APP));

        assertThat(frames(bounded)).hasSize(100);
        assertThat(bounded.toString()).doesNotContain("fms-eu-prod-gateway");
    }

    @Test
    void limitsLokiToFiveHundredRowsAndTwoMegabytes() {
        ObjectNode response = response();
        frames(response).add(frame(EU_APP, "Line", 300));
        frames(response).add(frame(EU_APP, "Line", 300));

        JsonNode bounded = parse(boundary.loki(response.toString(), EU_APP));

        assertThat(totalRows(frames(bounded))).isEqualTo(500);
        assertThatThrownBy(() -> boundary.loki("x".repeat(2_000_001), EU_APP))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsTempoSearchAndSelectsOnlyThreeDetailTraceIds() {
        ObjectNode response = response();
        ObjectNode frame = frame(EU_APP, "traceID", 25);
        ArrayNode traceIds = (ArrayNode) frame.path("data").path("values").get(1);
        traceIds.removeAll();
        for (int index = 0; index < 25; index++) {
            traceIds.add(traceId(index));
        }
        frames(response).add(frame);

        var bounded = boundary.tempo(response.toString(), EU_APP);

        assertThat(totalRows(frames(parse(bounded.search())))).isEqualTo(20);
        assertThat(bounded.detailTraceIds()).hasSize(3);
        String evidence = boundary.tempoEvidence(
            bounded,
            List.of("{\"trace\":1}", "{\"trace\":2}", "{\"trace\":3}", "{\"trace\":4}")
        );
        assertThat(parse(evidence).path("details").size()).isEqualTo(3);
    }

    private ObjectNode response() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("results").putObject("A").putArray("frames");
        return root;
    }

    private ObjectNode frame(String serviceName, String valueField, int rows) {
        ObjectNode frame = objectMapper.createObjectNode();
        ArrayNode fields = frame.putObject("schema").putArray("fields");
        fields.addObject().put("name", "Time");
        fields.addObject().put("name", valueField)
            .putObject("labels").put("service_name", serviceName);
        ArrayNode values = frame.putObject("data").putArray("values");
        ArrayNode times = values.addArray();
        ArrayNode data = values.addArray();
        for (int index = 0; index < rows; index++) {
            times.add(index);
            data.add(valueField + '-' + index);
        }
        return frame;
    }

    private ArrayNode frames(JsonNode response) {
        return (ArrayNode) response.path("results").path("A").path("frames");
    }

    private int totalRows(ArrayNode frames) {
        int rows = 0;
        for (JsonNode frame : frames) {
            rows += frame.path("data").path("values").path(0).size();
        }
        return rows;
    }

    private JsonNode parse(String value) {
        return objectMapper.readTree(value);
    }

    private String traceId(int index) {
        String suffix = Integer.toHexString(index);
        return "0123456789abcdef" + "0".repeat(16 - suffix.length()) + suffix;
    }
}
