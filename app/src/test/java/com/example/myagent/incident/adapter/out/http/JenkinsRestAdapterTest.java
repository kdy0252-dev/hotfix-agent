package com.example.myagent.incident.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.myagent.global.configuration.JenkinsProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JenkinsRestAdapterTest {
    private static final String BUILD_PATH = "/job/FMS-EU/job/main/181";

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private JenkinsRestAdapter adapter;
    private String buildResult;

    @BeforeEach
    void setUp() throws Exception {
        buildResult = "FAILURE";
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.start();
        adapter = new JenkinsRestAdapter(
            new JenkinsProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "FMS-EU",
                "jenkins-user",
                "jenkins-token",
                true
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
    void readsOnlyTheSelectedBuildAndBoundsFailureEvidence() {
        var result = adapter.collect(request()).get();

        assertThat(paths).containsExactly(
            BUILD_PATH + "/api/json",
            BUILD_PATH + "/consoleText",
            BUILD_PATH + "/testReport/api/json"
        );
        String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
            "jenkins-user:jenkins-token".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(authorizations).allMatch(expectedAuthorization::equals);
        assertThat(result.revision()).isEqualTo("commit-181");
        assertThat(result.relevantLogLines()).hasSize(200);
        assertThat(result.testReport()).contains("failed-test");
        assertThat(result.evidenceRefs())
            .allMatch(reference -> reference.endsWith("collectedAt=2026-08-20T01:00:00Z"));
    }

    @Test
    void rejectsASuccessfulBuildBeforeReadingConsoleOrTests() {
        buildResult = "SUCCESS";

        var result = adapter.collect(request());

        assertThat(result.getLeft().code()).isEqualTo("JENKINS_BUILD_NOT_ELIGIBLE");
        assertThat(paths).containsExactly(BUILD_PATH + "/api/json");
    }

    @Test
    void listsOnlyFailedPullRequestJobsByMostRecentBuild() {
        var result = adapter.findFailedPullRequestBuilds().get();

        assertThat(result).extracting(build -> build.pullRequestNumber())
            .containsExactly(1293L, 1292L);
        assertThat(result).allSatisfy(build -> {
            assertThat(build.result()).isEqualTo("FAILURE");
            assertThat(build.jobPath()).startsWith("FMS-EU/job/PR-");
        });
    }

    @Test
    void readsTheActualPipelineStagesForAnExplicitCiRefresh() {
        String buildUrl = "http://127.0.0.1:" + server.getAddress().getPort() + BUILD_PATH;

        var snapshot = adapter.refreshPullRequestBuild(buildUrl).get();

        assertThat(paths).containsExactly(
            BUILD_PATH + "/api/json",
            BUILD_PATH + "/wfapi/describe"
        );
        assertThat(snapshot.result()).isEqualTo("IN_PROGRESS");
        assertThat(snapshot.pipeline().stages())
            .extracting(HotfixResource.CiStage::name, HotfixResource.CiStage::status)
            .containsExactly(
                tuple("Checkout SCM", "SUCCESS"),
                tuple("Test", "IN_PROGRESS"),
                tuple("Image Build", "NOT_EXECUTED")
            );
    }

    private AnalysisRequest.Jenkins request() {
        return new AnalysisRequest.Jenkins(
            "FMS-EU/job/main",
            181,
            SourceSpec.branch("main")
        );
    }

    private void serve(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/job/FMS-EU/api/json")) {
            respond(exchange, pullRequestJobs());
        } else if (path.endsWith("/api/json") && !path.contains("testReport")) {
            respond(exchange, metadata());
        } else if (path.endsWith("/consoleText")) {
            respond(exchange, console());
        } else if (path.endsWith("/testReport/api/json")) {
            respond(exchange, "{\"failCount\":1,\"name\":\"failed-test\"}");
        } else if (path.endsWith("/wfapi/describe")) {
            respond(exchange, pipeline());
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private String metadata() {
        return """
            {
              "building": false,
              "result": "%s",
              "actions": [{
                "lastBuiltRevision": {
                  "SHA1": "synthetic-merge-commit",
                  "branch": [{"SHA1":"commit-181","name":"PR-1292"}]
                }
              }]
            }
            """.formatted(buildResult);
    }

    private String console() {
        return IntStream.range(0, 250)
            .mapToObj(index -> "ERROR failure line " + index)
            .reduce((left, right) -> left + '\n' + right)
            .orElse("");
    }

    private String pullRequestJobs() {
        return """
            {
              "jobs": [
                {"name":"PR-1292","lastBuild":{"number":1,"result":"FAILURE",
                  "timestamp":1787100000000,"url":"https://jenkins/job/PR-1292/1/"}},
                {"name":"PR-1293","lastBuild":{"number":2,"result":"FAILURE",
                  "timestamp":1787200000000,"url":"https://jenkins/job/PR-1293/2/"}},
                {"name":"PR-1294","lastBuild":{"number":3,"result":"SUCCESS",
                  "timestamp":1787300000000,"url":"https://jenkins/job/PR-1294/3/"}},
                {"name":"main","lastBuild":{"number":181,"result":"FAILURE",
                  "timestamp":1787400000000,"url":"https://jenkins/job/main/181/"}}
              ]
            }
            """;
    }

    private String pipeline() {
        return """
            {
              "status":"IN_PROGRESS",
              "stages":[
                {"id":"10","name":"Checkout SCM","status":"SUCCESS",
                 "startTimeMillis":1787527475678,"durationMillis":74215},
                {"id":"31","name":"Test","status":"IN_PROGRESS",
                 "startTimeMillis":1787527557221,"durationMillis":47193},
                {"id":"45","name":"Image Build","status":"NOT_EXECUTED",
                 "startTimeMillis":0,"durationMillis":0}
              ]
            }
            """;
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
