package com.example.myagent.incident.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BitbucketSourceRevisionAdapterTest {
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private BitbucketSourceRevisionAdapter adapter;
    private String pullRequestState;

    @BeforeEach
    void setUp() throws Exception {
        pullRequestState = "OPEN";
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.start();
        adapter = new BitbucketSourceRevisionAdapter(
            new BitbucketProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                URI.create("https://bitbucket.org"),
                "autocrypt",
                "fms",
                "bitbucket-token"
            ),
            new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fixesABranchToItsHeadCommitAndDestination() {
        var revision = adapter.resolve(SourceSpec.branch("feature/hot fix")).get();

        assertThat(paths).containsExactly(
            "/repositories/autocrypt/fms/refs/branches/feature%2Fhot%20fix"
        );
        assertThat(revision.commit()).isEqualTo("branch-commit");
        assertThat(revision.destinationBranch()).isEqualTo("feature/hot fix");
        assertThat(revision.provenance()).isEqualTo("bitbucket:branch:feature/hot fix");
    }

    @Test
    void fixesAnOpenPullRequestToItsSourceCommitAndBranch() {
        var revision = adapter.resolve(SourceSpec.pullRequest(1285)).get();

        assertThat(paths).containsExactly("/repositories/autocrypt/fms/pullrequests/1285");
        assertThat(revision.commit()).isEqualTo("pr-commit");
        assertThat(revision.destinationBranch()).isEqualTo("feature/pr-1285");
        assertThat(revision.provenance()).isEqualTo("bitbucket:pull-request:1285");
    }

    @Test
    void rejectsAClosedPullRequest() {
        pullRequestState = "MERGED";

        var result = adapter.resolve(SourceSpec.pullRequest(1285));

        assertThat(result.getLeft().code()).isEqualTo("SOURCE_RESOLUTION_FAILED");
    }

    private void serve(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getRawPath());
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer bitbucket-token");
        String response = exchange.getRequestURI().getPath().contains("pullrequests")
            ? pullRequestResponse() : "{\"target\":{\"hash\":\"branch-commit\"}}";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private String pullRequestResponse() {
        return """
            {
              "state": "%s",
              "source": {
                "commit": {"hash": "pr-commit"},
                "branch": {"name": "feature/pr-1285"}
              }
            }
            """.formatted(pullRequestState);
    }
}
