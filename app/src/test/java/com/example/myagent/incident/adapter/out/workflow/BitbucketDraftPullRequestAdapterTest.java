package com.example.myagent.incident.adapter.out.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.global.configuration.JenkinsProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.JenkinsfileProfile;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.StageResult;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.VerificationProvenance;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.ChangeSummary;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Review;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import com.example.myagent.incident.application.port.out.PullRequestPort.IncidentArtifact;
import com.example.myagent.incident.application.port.out.PullRequestPort.PatchArtifact;
import com.example.myagent.incident.application.port.out.PullRequestPort.PublicationRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class BitbucketDraftPullRequestAdapterTest {
    private static final String BASE_COMMIT = "base123";
    private static final String PATCH_COMMIT = "patch123";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private BitbucketDraftPullRequestAdapter adapter;
    private boolean existingDraft;
    private String sourceCommit;
    private String sourceCommitReference;
    private String expectedPatchCommit;
    private String patchCommitReference;
    private String postedBody;

    @BeforeEach
    void setUp() throws Exception {
        existingDraft = true;
        sourceCommit = BASE_COMMIT;
        sourceCommitReference = BASE_COMMIT;
        expectedPatchCommit = PATCH_COMMIT;
        patchCommitReference = PATCH_COMMIT;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.start();
        URI apiUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        adapter = new BitbucketDraftPullRequestAdapter(
            new BitbucketProperties(
                apiUrl,
                URI.create("https://bitbucket.org"),
                "autocrypt",
                "fms",
                "bitbucket-token"
            ),
            new JenkinsProperties(
                URI.create("https://jenkins.example.com"),
                "FMS-EU",
                "user",
                "token",
                true
            ),
            new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void reusesTheExpectedExistingDraftWithoutPublishingAgain() {
        var result = adapter.publishDraft(request(verification(0))).get();

        assertThat(requests).hasSize(3);
        assertThat(requests.get(0)).contains("GET /repositories/autocrypt/fms/refs/branches/main");
        assertThat(requests.get(1)).contains("GET /repositories/autocrypt/fms/pullrequests?");
        assertThat(requests.get(2))
            .contains("GET /repositories/autocrypt/fms/commit/patch123");
        assertThat(result.pullRequestUrl()).isEqualTo("https://bitbucket.example/pr/99");
        assertThat(result.ciJobUrl())
            .isEqualTo("https://jenkins.example.com/job/FMS-EU/job/PR-99/");
    }

    @Test
    void rejectsFailedParityBeforeAnyBitbucketReadOrWrite() {
        var result = adapter.publishDraft(request(verification(1)));

        assertThat(result.getLeft().code()).isEqualTo("DRAFT_PULL_REQUEST_FAILED");
        assertThat(requests).isEmpty();
    }

    @Test
    void canonicalizesAnAbbreviatedPullRequestCommitBeforeFreshnessCheck() {
        sourceCommitReference = "base-short";

        var result = adapter.publishDraft(request(
            verification(0),
            BASE_COMMIT,
            PATCH_COMMIT,
            "/not-used",
            SourceSpec.pullRequest(1285)
        ));

        assertThat(result.isRight()).isTrue();
        assertThat(requests).hasSize(4);
        assertThat(requests.get(0))
            .contains("GET /repositories/autocrypt/fms/pullrequests/1285");
        assertThat(requests.get(1))
            .contains("GET /repositories/autocrypt/fms/commit/base-short");
        assertThat(requests.get(2))
            .contains("GET /repositories/autocrypt/fms/pullrequests?");
        assertThat(requests.get(3))
            .contains("GET /repositories/autocrypt/fms/commit/patch123");
    }

    @Test
    void pushesTheHotfixBranchAndCreatesAReviewerlessDraft(@TempDir Path temporaryDirectory)
        throws Exception {
        existingDraft = false;
        Path remoteRoot = temporaryDirectory.resolve("remotes");
        Path bareRepository = remoteRoot.resolve("autocrypt/fms.git");
        Path worktree = temporaryDirectory.resolve("worktree");
        Files.createDirectories(bareRepository.getParent());
        Files.createDirectories(worktree);
        run(temporaryDirectory, List.of("git", "init", "--bare", bareRepository.toString()));
        run(worktree, List.of("git", "init"));
        Files.writeString(worktree.resolve("Booking.java"), "class Booking {}\n");
        run(worktree, List.of("git", "add", "Booking.java"));
        run(worktree, List.of(
            "git", "-c", "user.name=Agent Test", "-c", "user.email=agent@example.com",
            "commit", "-m", "base"
        ));
        sourceCommit = output(worktree, List.of("git", "rev-parse", "HEAD"));
        Files.writeString(worktree.resolve("Booking.java"), "class Booking { boolean safe; }\n");
        run(worktree, List.of("git", "add", "Booking.java"));
        run(worktree, List.of(
            "git", "-c", "user.name=Agent Test", "-c", "user.email=agent@example.com",
            "commit", "-m", "patch"
        ));
        expectedPatchCommit = output(worktree, List.of("git", "rev-parse", "HEAD"));
        patchCommitReference = expectedPatchCommit.substring(0, 12);
        adapter = adapter(remoteRoot.toUri());

        var result = adapter.publishDraft(request(
            verification(sourceCommit, expectedPatchCommit, 0),
            sourceCommit,
            expectedPatchCommit,
            worktree.toString()
        ));

        assertThat(result.isRight()).isTrue();
        assertThat(output(worktree, List.of(
            "git", "ls-remote", bareRepository.toUri().toString(),
            "refs/heads/agent/hotfix/analysis-null-response"
        ))).startsWith(expectedPatchCommit);
        assertThat(postedBody)
            .contains("\"draft\":true", "\"reviewers\":[]")
            .contains("\"name\":\"agent/hotfix/analysis-null-response\"")
            .contains("\"name\":\"main\"")
            .contains("운영 담당: BE팀");
    }

    private PublicationRequest request(Verification verification) {
        return request(verification, BASE_COMMIT, PATCH_COMMIT, "/not-used");
    }

    private PublicationRequest request(
        Verification verification,
        String baseCommit,
        String patchCommit,
        String worktreePath
    ) {
        return request(
            verification,
            baseCommit,
            patchCommit,
            worktreePath,
            SourceSpec.branch("main")
        );
    }

    private PublicationRequest request(
        Verification verification,
        String baseCommit,
        String patchCommit,
        String worktreePath,
        SourceSpec source
    ) {
        var analysis = new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "request-hash"),
            new AnalysisSession.Snapshot(
                source,
                new SourceRevision(baseCommit, "main", "bitbucket:branch:main"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(AnalysisSession.Status.CANDIDATES_READY, List.of(), null)
        );
        var candidate = new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1", "Null response", "Null dereference", 0.95,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(List.of("Booking.java:10"), List.of("jenkins:181"), List.of()),
            new BugCandidate.Recommendation("Guard null", "Run parity")
        );
        var patch = new AppliedPatch(
            new Workspace(
                worktreePath,
                "agent/hotfix/analysis-null-response",
                baseCommit,
                Map.of("eu/eu-app/src/main/java/Booking.java", "class Booking {}")
            ),
            new ChangeSummary(List.of("eu/eu-app/src/main/java/Booking.java"), 2),
            patchCommit
        );
        return new PublicationRequest(
            "hotfix-1",
            new IncidentArtifact(analysis, candidate),
            new PatchArtifact(patch, verification, new Review(true, "approved", List.of()))
        );
    }

    private Verification verification(int exitCode) {
        return verification(BASE_COMMIT, PATCH_COMMIT, exitCode);
    }

    private Verification verification(String baseCommit, String patchCommit, int exitCode) {
        return new Verification(
            1,
            new VerificationProvenance(
                baseCommit,
                patchCommit,
                new JenkinsfileProfile("eu/Jenkinsfile", "jenkins-hash", 1)
            ),
            List.of(
                new StageResult("jenkins-gradle-verification", exitCode, true, "gradle"),
                new StageResult("jenkins-coverage-report", 0, true, "coverage"),
                new StageResult("jenkins-image-build", 0, true, "image"),
                new StageResult("jenkins-integration-test", 0, true, "integration")
            )
        );
    }

    private void serve(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + ' ' + exchange.getRequestURI());
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer bitbucket-token");
        String path = exchange.getRequestURI().getPath();
        String response;
        if (path.endsWith("/pullrequests/1285")) {
            response = """
                {
                  "state":"OPEN",
                  "source":{"commit":{"hash":"%s"}}
                }
                """.formatted(sourceCommitReference);
        } else if (path.contains("/commit/")) {
            String resolvedCommit = path.endsWith('/' + sourceCommitReference)
                ? sourceCommit : expectedPatchCommit;
            response = "{\"hash\":\"" + resolvedCommit + "\"}";
        } else if (path.contains("refs/branches")) {
            response = "{\"target\":{\"hash\":\"" + sourceCommit + "\"}}";
        } else if ("POST".equals(exchange.getRequestMethod())) {
            postedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            response = pullRequestResponse();
        } else {
            response = existingDraft ? "{\"values\":[" + pullRequestResponse() + "]}"
                : "{\"values\":[]}";
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private String pullRequestResponse() {
        return """
            {
              "id":99,
              "draft":true,
              "source":{"commit":{"hash":"%s"}},
              "links":{"html":{"href":"https://bitbucket.example/pr/99"}}
            }
            """.formatted(patchCommitReference);
    }

    private BitbucketDraftPullRequestAdapter adapter(URI gitBaseUrl) {
        URI apiUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new BitbucketDraftPullRequestAdapter(
            new BitbucketProperties(apiUrl, gitBaseUrl, "autocrypt", "fms", "bitbucket-token"),
            new JenkinsProperties(
                URI.create("https://jenkins.example.com"),
                "FMS-EU",
                "user",
                "token",
                true
            ),
            new ObjectMapper()
        );
    }

    private void run(Path directory, List<String> command) throws Exception {
        var result = LocalProcessExecutor.run(directory, command);
        assertThat(result.successful())
            .withFailMessage("Command failed: %s%n%s", command, result.output())
            .isTrue();
    }

    private String output(Path directory, List<String> command) throws Exception {
        var result = LocalProcessExecutor.run(directory, command);
        assertThat(result.successful()).withFailMessage(result.output()).isTrue();
        return result.output().trim();
    }
}
