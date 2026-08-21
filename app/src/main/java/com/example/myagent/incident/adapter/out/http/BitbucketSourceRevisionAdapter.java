package com.example.myagent.incident.adapter.out.http;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.SourceRevisionPort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Adapter
@Component
public class BitbucketSourceRevisionAdapter implements SourceRevisionPort {
    private final BitbucketProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public BitbucketSourceRevisionAdapter(
        BitbucketProperties properties,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Either<IncidentFailure, SourceRevision> resolve(SourceSpec source) {
        return Try.of(() -> source.type() == SourceSpec.Type.BRANCH
            ? resolveBranch(source) : resolvePullRequest(source)
        ).toEither().mapLeft(exception -> new IncidentFailure(
            "SOURCE_RESOLUTION_FAILED",
            "Bitbucket source branch 또는 PR을 확인하지 못했습니다."
        ));
    }

    private SourceRevision resolveBranch(SourceSpec source) throws Exception {
        if (source.branchName() == null || source.branchName().isBlank()) {
            throw new IllegalArgumentException("branchName is required");
        }
        JsonNode response = get(repositoryUrl("refs/branches/" + encode(source.branchName())));
        return new SourceRevision(
            required(response.path("target").path("hash").asString(), "branch commit"),
            source.branchName(),
            "bitbucket:branch:" + source.branchName()
        );
    }

    private SourceRevision resolvePullRequest(SourceSpec source) throws Exception {
        if (source.pullRequestId() == null || source.pullRequestId() <= 0) {
            throw new IllegalArgumentException("pullRequestId is required");
        }
        JsonNode response = get(repositoryUrl("pullrequests/" + source.pullRequestId()));
        if (!"OPEN".equals(response.path("state").asString())) {
            throw new IllegalStateException("Pull request is not open");
        }
        String sourceCommit = required(
            response.path("source").path("commit").path("hash").asString(),
            "pull request source commit"
        );
        return new SourceRevision(
            canonicalCommit(sourceCommit),
            required(
                response.path("source").path("branch").path("name").asString(),
                "pull request source branch"
            ),
            "bitbucket:pull-request:" + source.pullRequestId()
        );
    }

    private String canonicalCommit(String reference) throws Exception {
        JsonNode response = get(repositoryUrl("commit/" + encode(reference)));
        return required(response.path("hash").asString(), "canonical commit");
    }

    private JsonNode get(URI uri) throws Exception {
        var request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + properties.token())
            .header("Accept", "application/json")
            .GET()
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bitbucket returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private URI repositoryUrl(String suffix) {
        return URI.create(properties.baseUrl().toString().replaceAll("/$", "")
            + "/repositories/" + encode(properties.workspace()) + '/'
            + encode(properties.repository()) + '/' + suffix);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Bitbucket response has no " + field);
        }
        return value;
    }
}
