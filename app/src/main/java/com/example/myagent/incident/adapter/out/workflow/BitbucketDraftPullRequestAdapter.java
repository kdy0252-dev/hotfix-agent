package com.example.myagent.incident.adapter.out.workflow;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.global.configuration.JenkinsProperties;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.StageResult;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Publication;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.PullRequestPort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Adapter
@Component
public class BitbucketDraftPullRequestAdapter implements PullRequestPort {
    private static final Set<PosixFilePermission> OWNER_EXECUTABLE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );

    private final BitbucketProperties bitbucket;
    private final JenkinsProperties jenkins;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public BitbucketDraftPullRequestAdapter(
        BitbucketProperties bitbucket,
        JenkinsProperties jenkins,
        ObjectMapper objectMapper
    ) {
        this.bitbucket = bitbucket;
        this.jenkins = jenkins;
        this.objectMapper = objectMapper;
    }

    @Override
    public Either<IncidentFailure, Publication> publishDraft(PublicationRequest request) {
        return Try.of(() -> {
            requireApprovedVerification(request);
            requireFreshSource(request);
            var existing = findOpenPullRequest(request.patch().workspace().branchName());
            if (!existing.isMissingNode()) {
                requireExpectedDraft(existing, request.patch().patchCommit());
                return publication(existing);
            }
            pushBranch(request);
            requireFreshSource(request);
            JsonNode pullRequest = createDraft(request);
            requireExpectedDraft(pullRequest, request.patch().patchCommit());
            return publication(pullRequest);
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "DRAFT_PULL_REQUEST_FAILED",
            "동일 커밋의 hotfix 브랜치와 Draft PR을 생성하지 못했습니다."
        ));
    }

    private void requireApprovedVerification(PublicationRequest request) {
        var verification = request.verification();
        var provenance = verification.provenance();
        var jenkinsfile = provenance == null ? null : provenance.jenkinsfile();
        Set<String> executedStages = verification.stages().stream()
            .map(StageResult::name)
            .collect(Collectors.toSet());
        boolean successful = !verification.stages().isEmpty()
            && verification.stages().stream()
            .allMatch(stage -> stage.required() && stage.exitCode() == 0);
        if (provenance == null || jenkinsfile == null
            || !request.patch().workspace().baseCommit().equals(provenance.baseCommit())
            || !request.patch().patchCommit().equals(provenance.patchCommit())
            || !JenkinsParityProfile.JENKINSFILE.equals(jenkinsfile.path())
            || jenkinsfile.sha256() == null || jenkinsfile.sha256().isBlank()
            || jenkinsfile.profileVersion() < 1
            || !JenkinsParityProfile.requiredStages().equals(executedStages)
            || !successful
            || !request.patch().workspace().branchName().startsWith("agent/hotfix/")) {
            throw new IllegalStateException("Draft PR requires approved Jenkins parity evidence");
        }
    }

    private void requireFreshSource(PublicationRequest request) throws Exception {
        String currentCommit = resolveCurrentSourceCommit(request.analysis().snapshot().source());
        if (!request.analysis().snapshot().sourceRevision().commit().equals(currentCommit)) {
            throw new IllegalStateException("Source revision changed after analysis");
        }
    }

    private String resolveCurrentSourceCommit(SourceSpec source) throws Exception {
        if (source.type() == SourceSpec.Type.BRANCH) {
            JsonNode branch = get(repositoryUrl("refs/branches/" + encode(source.branchName())));
            return branch.path("target").path("hash").asString();
        }
        JsonNode pullRequest = get(repositoryUrl("pullrequests/" + source.pullRequestId()));
        if (!"OPEN".equals(pullRequest.path("state").asString())) {
            throw new IllegalStateException("Source pull request is no longer open");
        }
        String reference = pullRequest.path("source").path("commit").path("hash").asString();
        JsonNode commit = get(repositoryUrl("commit/" + encode(reference)));
        return commit.path("hash").asString();
    }

    private JsonNode findOpenPullRequest(String branchName) throws Exception {
        String query = "source.branch.name=\"" + branchName + "\"";
        URI uri = repositoryUrl("pullrequests?state=OPEN&q=" + encode(query) + "&pagelen=1");
        JsonNode values = get(uri).path("values");
        return values.isArray() && !values.isEmpty() ? values.get(0) : objectMapper.missingNode();
    }

    private void pushBranch(PublicationRequest request) throws Exception {
        Path worktree = Path.of(request.patch().workspace().worktreePath());
        Path askPass = Files.createTempFile("bitbucket-askpass-", ".sh");
        Try.run(() -> {
            Files.writeString(askPass, """
                #!/bin/sh
                case "$1" in
                  *Username*) printf '%s\\n' 'x-token-auth' ;;
                  *) printf '%s\\n' "$BITBUCKET_TOKEN" ;;
                esac
                """);
            Files.setPosixFilePermissions(askPass, OWNER_EXECUTABLE);
            String remote = bitbucket.gitBaseUrl().toString().replaceAll("/$", "") + '/'
                + encodePath(bitbucket.workspace()) + '/'
                + encodePath(bitbucket.repository()) + ".git";
            var result = LocalProcessExecutor.run(
                worktree,
                List.of(
                    "git", "push", "--set-upstream", remote,
                    "HEAD:refs/heads/" + request.patch().workspace().branchName()
                ),
                Map.of(
                    "GIT_ASKPASS", askPass.toString(),
                    "GIT_TERMINAL_PROMPT", "0",
                    "BITBUCKET_TOKEN", bitbucket.token()
                ),
                Duration.ofMinutes(5)
            );
            if (!result.successful()) {
                throw new IllegalStateException("Git push failed");
            }
        }).andFinally(() -> delete(askPass)).get();
    }

    private void delete(Path path) {
        Try.run(() -> Files.deleteIfExists(path)).get();
    }

    private JsonNode createDraft(PublicationRequest request) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", "[Agent Hotfix] " + request.candidate().identity().title());
        body.put("description", description(request));
        body.put("draft", true);
        body.put("close_source_branch", false);
        body.putObject("source").putObject("branch")
            .put("name", request.patch().workspace().branchName());
        body.putObject("destination").putObject("branch")
            .put("name", request.analysis().snapshot().sourceRevision().destinationBranch());
        body.putArray("reviewers");
        var httpRequest = HttpRequest.newBuilder(repositoryUrl("pullrequests"))
            .header("Authorization", "Bearer " + bitbucket.token())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        return send(httpRequest);
    }

    private String description(PublicationRequest request) {
        return """
            ## 자동 핫픽스 요약
            - 운영 담당: BE팀
            - 분석 ID: %s
            - 후보 ID: %s
            - 기준 커밋: `%s`
            - 패치 커밋: `%s`
            - 변경 파일/라인: %d / %d

            ## 원인 및 수정
            - 원인: %s
            - 수정: %s
            - 독립 리뷰: %s

            ## 검증
            - Jenkinsfile 경로: `%s`
            - Jenkinsfile SHA-256: `%s`
            - 승인 parity profile: %d
            - 단계: %s

            Draft PR 전용입니다. merge, tag, release, deploy는 자동 수행하지 않습니다.
            """.formatted(
                request.analysis().identity().analysisId(),
                request.candidate().identity().candidateId(),
                request.patch().workspace().baseCommit(),
                request.patch().patchCommit(),
                request.patch().changes().files().size(),
                request.patch().changes().changedLines(),
                request.candidate().identity().rootCause(),
                request.candidate().recommendation().fixSummary(),
                request.review().summary(),
                request.verification().provenance().jenkinsfile().path(),
                request.verification().provenance().jenkinsfile().sha256(),
                request.verification().provenance().jenkinsfile().profileVersion(),
                request.verification().stages()
            );
    }

    private Publication publication(JsonNode pullRequest) {
        long pullRequestId = pullRequest.path("id").asLong();
        String pullRequestUrl = pullRequest.path("links").path("html").path("href").asString();
        String ciJobUrl = jenkins.baseUrl().toString().replaceAll("/$", "")
            + "/job/" + encodePath(jenkins.rootJob()) + "/job/PR-" + pullRequestId + '/';
        return new Publication(pullRequestUrl, ciJobUrl);
    }

    private void requireExpectedDraft(JsonNode pullRequest, String patchCommit) throws Exception {
        String sourceReference = pullRequest.path("source").path("commit").path("hash").asString();
        JsonNode commit = get(repositoryUrl("commit/" + encode(sourceReference)));
        String sourceCommit = commit.path("hash").asString();
        if (!pullRequest.path("draft").asBoolean() || !patchCommit.equals(sourceCommit)) {
            throw new IllegalStateException("Pull request is not the expected Draft patch");
        }
    }

    private JsonNode get(URI uri) throws Exception {
        var request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + bitbucket.token())
            .header("Accept", "application/json")
            .GET()
            .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) throws Exception {
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bitbucket returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private URI repositoryUrl(String suffix) {
        return URI.create(bitbucket.baseUrl().toString().replaceAll("/$", "")
            + "/repositories/" + encodePath(bitbucket.workspace()) + '/'
            + encodePath(bitbucket.repository()) + '/' + suffix);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodePath(String value) {
        return encode(value);
    }
}
