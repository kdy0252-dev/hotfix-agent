package com.example.myagent.incident.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BitbucketSourceContextAdapterTest {
    private static final String SOURCE_PATH =
        "eu/eu-app/src/main/java/example/BookingService.java";

    private HttpServer server;
    private BitbucketSourceContextAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
            "/2.0/repositories/autocrypt/fms/src/abc123/" + SOURCE_PATH,
            this::serveSource
        );
        server.start();
        adapter = new BitbucketSourceContextAdapter(
            new BitbucketProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/2.0"),
                URI.create("https://bitbucket.org"),
                "autocrypt",
                "fms",
                "test-token"
            ),
            new SensitiveEvidenceRedactor()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsOnlyTheEvidencePathAtTheFixedCommit() {
        var evidence = new AnalysisEvidence.Jenkins(
            "https://jenkins.example/build/181",
            "abc123",
            List.of("at /workspace/fms/" + SOURCE_PATH + ":84"),
            "failed test",
            List.of("jenkins:181")
        );

        var result = adapter.read(
            evidence,
            new SourceRevision("abc123", "main", "bitbucket:branch:main")
        ).get();

        assertThat(result.files()).containsEntry(
            SOURCE_PATH,
            "class BookingService {}\n"
        );
    }

    @Test
    void discoversMultipleObservabilitySourceFilesAndKeepsRelevantLines() {
        String mapperPath = "eu/booking/src/main/java/example/BookingMapper.java";
        String servicePath = "eu/booking/src/main/java/example/BookingService.java";
        server.createContext(
            "/2.0/repositories/autocrypt/fms/src/abc123/eu",
            exchange -> serveJson(exchange, """
                {"values":[
                  {"type":"commit_file","path":"%s"},
                  {"type":"commit_file","path":"%s"}
                ]}
                """.formatted(mapperPath, servicePath))
        );
        server.createContext(
            "/2.0/repositories/autocrypt/fms/src/abc123/" + mapperPath,
            exchange -> serveText(exchange, "class BookingMapper { void map() {} }\n")
        );
        server.createContext(
            "/2.0/repositories/autocrypt/fms/src/abc123/" + servicePath,
            exchange -> serveText(exchange, "class BookingService { void start() {} }\n")
        );

        var evidence = new AnalysisEvidence.Observability(
            "fms-eu-prod",
            "fms-eu-prod-app",
            "",
            "",
            "at example.BookingService.start(BookingService.java:42)\n"
                + "at example.BookingMapper.map(BookingMapper.java:8)",
            "",
            List.of("loki:trace")
        );

        var result = adapter.read(
            evidence,
            new SourceRevision("abc123", "main", "bitbucket:branch:main")
        ).get();

        assertThat(result.files()).containsKeys(servicePath, mapperPath);
        assertThat(result.files().get(mapperPath)).contains("1: class BookingMapper");
    }

    private void serveSource(HttpExchange exchange) throws IOException {
        serveText(exchange, "class BookingService {}\n");
    }

    private void serveJson(HttpExchange exchange, String value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        serveText(exchange, value);
    }

    private void serveText(HttpExchange exchange, String value) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer test-token");
        byte[] response = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (var body = exchange.getResponseBody()) {
            body.write(response);
        }
    }
}
