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

    private void serveSource(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer test-token");
        byte[] response = "class BookingService {}\n".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (var body = exchange.getResponseBody()) {
            body.write(response);
        }
    }
}
