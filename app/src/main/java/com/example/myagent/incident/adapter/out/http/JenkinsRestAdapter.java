package com.example.myagent.incident.adapter.out.http;

import com.example.myagent.global.configuration.JenkinsProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.CiPipeline;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.CiStage;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.CiTiming;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.JenkinsDashboardPort;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Adapter
@Component
public class JenkinsRestAdapter implements JenkinsEvidencePort, JenkinsDashboardPort {
    private static final int MAX_LOG_LINES = 200;
    private static final int MAX_TEST_REPORT_CHARS = 100_000;
    private static final Pattern PULL_REQUEST_JOB = Pattern.compile("PR-(\\d+)");

    private final JenkinsProperties properties;
    private final ObjectMapper objectMapper;
    private final SensitiveEvidenceRedactor redactor;
    private final Clock clock;
    private final HttpClient httpClient;

    public JenkinsRestAdapter(
        JenkinsProperties properties,
        ObjectMapper objectMapper,
        SensitiveEvidenceRedactor redactor,
        Clock clock
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.clock = clock;
        this.httpClient = HttpClientFactory.create(properties.tlsVerify());
    }

    @Override
    public Either<IncidentFailure, BuildSnapshot> inspect(AnalysisRequest.Jenkins request) {
        return Try.of(() -> new BuildSnapshot(revision(failedBuildMetadata(request))))
            .toEither()
            .mapLeft(this::failure);
    }

    @Override
    public Either<IncidentFailure, AnalysisEvidence.Jenkins> collect(
        AnalysisRequest.Jenkins request
    ) {
        return Try.of(() -> collectEvidence(request))
            .toEither()
            .mapLeft(this::failure);
    }

    @Override
    public Either<IncidentFailure, CiBuildSnapshot> refreshPullRequestBuild(String buildUrl) {
        return Try.of(() -> {
            JsonNode metadata = getJson(URI.create(stripTrailingSlash(buildUrl) + "/api/json"));
            JsonNode build = metadata.has("lastBuild") ? metadata.path("lastBuild") : metadata;
            String resolvedUrl = build.path("url").asString(buildUrl);
            JsonNode pipeline = getOptionalJson(URI.create(
                stripTrailingSlash(resolvedUrl) + "/wfapi/describe"
            )).orElse(null);
            String result = pipeline == null
                ? build.path("result").asString()
                : pipeline.path("status").asString(build.path("result").asString());
            return new CiBuildSnapshot(
                resolvedUrl,
                new CiPipeline(result, pipelineStages(pipeline))
            );
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<IncidentFailure, List<FailedBuild>> findFailedPullRequestBuilds() {
        return Try.of(this::failedPullRequestBuilds)
            .toEither()
            .mapLeft(this::failure);
    }

    private List<FailedBuild> failedPullRequestBuilds() throws Exception {
        String tree = "jobs%5Bname,url,color,lastBuild%5Bnumber,result,timestamp,url%5D%5D";
        URI uri = URI.create(stripTrailingSlash(properties.baseUrl().toString())
            + "/job/" + properties.rootJob() + "/api/json?tree=" + tree);
        JsonNode jobs = getJson(uri).path("jobs");
        return jobs.isArray() ? StreamSupport.stream(jobs.spliterator(), false)
            .map(this::failedPullRequestBuild)
            .flatMap(Optional::stream)
            .sorted((left, right) -> right.timestamp().compareTo(left.timestamp()))
            .toList() : List.of();
    }

    private Optional<FailedBuild> failedPullRequestBuild(JsonNode job) {
        var matcher = PULL_REQUEST_JOB.matcher(job.path("name").asString());
        JsonNode lastBuild = job.path("lastBuild");
        if (!matcher.matches() || !"FAILURE".equals(lastBuild.path("result").asString())) {
            return Optional.empty();
        }
        String jobName = job.path("name").asString();
        return Optional.of(new FailedBuild(
            Long.parseLong(matcher.group(1)),
            properties.rootJob() + "/job/" + jobName,
            lastBuild.path("number").asLong(),
            lastBuild.path("result").asString(),
            Instant.ofEpochMilli(lastBuild.path("timestamp").asLong()),
            lastBuild.path("url").asString(job.path("url").asString())
        ));
    }

    private AnalysisEvidence.Jenkins collectEvidence(AnalysisRequest.Jenkins request) throws Exception {
        String buildUrl = buildUrl(request);
        JsonNode metadata = failedBuildMetadata(request);
        String console = getText(URI.create(buildUrl + "/consoleText"));
        String testReport = getOptionalText(URI.create(buildUrl + "/testReport/api/json"));
        String revision = revision(metadata);
        return new AnalysisEvidence.Jenkins(
            buildUrl,
            revision,
            relevantLines(redactor.redact(console)),
            truncate(redactor.redact(testReport), MAX_TEST_REPORT_CHARS),
            List.of(
                provenance(buildUrl),
                provenance(buildUrl + "/console"),
                provenance(buildUrl + "/testReport")
            )
        );
    }

    private JsonNode failedBuildMetadata(AnalysisRequest.Jenkins request) throws Exception {
        validateRequest(request);
        JsonNode metadata = getJson(URI.create(buildUrl(request) + "/api/json"));
        if (metadata.path("building").asBoolean()) {
            throw new IllegalStateException("Jenkins build is still running");
        }
        if (!"FAILURE".equals(metadata.path("result").asString())) {
            throw new IllegalArgumentException("Jenkins build is not a failed build");
        }
        return metadata;
    }

    private String buildUrl(AnalysisRequest.Jenkins request) {
        return stripTrailingSlash(properties.baseUrl().toString())
            + "/job/" + request.jobPath() + '/' + request.buildNumber();
    }

    private void validateRequest(AnalysisRequest.Jenkins request) {
        String requiredPrefix = properties.rootJob() + "/job/";
        if (request.jobPath() == null || !request.jobPath().startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("Jenkins job is outside the configured root job");
        }
        if (request.buildNumber() <= 0) {
            throw new IllegalArgumentException("buildNumber must be positive");
        }
    }

    private String revision(JsonNode metadata) {
        for (JsonNode action : metadata.path("actions")) {
            String branchSha = action.path("lastBuiltRevision")
                .path("branch")
                .path(0)
                .path("SHA1")
                .asString();
            if (!branchSha.isBlank()) {
                return branchSha;
            }
            String sha = action.path("lastBuiltRevision").path("SHA1").asString();
            if (!sha.isBlank()) {
                return sha;
            }
        }
        return "";
    }

    private List<String> relevantLines(String console) {
        List<String> selected = console.lines()
            .filter(this::isFailureLine)
            .limit(MAX_LOG_LINES)
            .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        List<String> allLines = console.lines().toList();
        return allLines.subList(Math.max(0, allLines.size() - MAX_LOG_LINES), allLines.size());
    }

    private boolean isFailureLine(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.contains("error")
            || normalized.contains("exception")
            || normalized.contains("failed")
            || normalized.contains("failure")
            || normalized.contains("caused by")
            || normalized.contains(" at ");
    }

    private JsonNode getJson(URI uri) throws Exception {
        return objectMapper.readTree(getText(uri));
    }

    private Optional<JsonNode> getOptionalJson(URI uri) throws Exception {
        String response = getOptionalText(uri);
        return response.isBlank() ? Optional.empty() : Optional.of(objectMapper.readTree(response));
    }

    private List<CiStage> pipelineStages(JsonNode pipeline) {
        if (pipeline == null || !pipeline.path("stages").isArray()) {
            return List.of();
        }
        return StreamSupport.stream(pipeline.path("stages").spliterator(), false)
            .map(stage -> new CiStage(
                stage.path("id").asString(),
                stage.path("name").asString(),
                stage.path("status").asString("UNKNOWN"),
                new CiTiming(
                    stage.path("startTimeMillis").asLong(),
                    stage.path("durationMillis").asLong()
                ),
                stage.path("error").path("message").asString(null)
            ))
            .toList();
    }

    private String getOptionalText(URI uri) throws Exception {
        var response = send(uri);
        return response.statusCode() == 404 ? "" : requireSuccess(response);
    }

    private String getText(URI uri) throws Exception {
        return requireSuccess(send(uri));
    }

    private HttpResponse<String> send(URI uri) throws Exception {
        String credentials = properties.username() + ':' + properties.apiToken();
        var request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
            .header("Accept", "application/json")
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Jenkins returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private String provenance(String source) {
        return source + "|collectedAt=" + clock.instant();
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private IncidentFailure failure(Throwable throwable) {
        String code = throwable instanceof IllegalArgumentException
            ? "JENKINS_BUILD_NOT_ELIGIBLE" : "JENKINS_READ_FAILED";
        return new IncidentFailure(code, throwable.getMessage());
    }
}
