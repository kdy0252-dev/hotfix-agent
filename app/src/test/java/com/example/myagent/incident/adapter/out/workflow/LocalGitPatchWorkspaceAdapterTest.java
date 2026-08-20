package com.example.myagent.incident.adapter.out.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.FileUpdate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalGitPatchWorkspaceAdapterTest {
    private static final String SOURCE_PATH =
        "eu/eu-app/src/main/java/example/BookingService.java";

    @TempDir
    Path temporaryDirectory;

    private Path repository;
    private LocalGitPatchWorkspaceAdapter adapter;
    private String baseCommit;

    @BeforeEach
    void setUp() throws Exception {
        repository = temporaryDirectory.resolve("fms");
        Files.createDirectories(repository.resolve(SOURCE_PATH).getParent());
        Files.writeString(repository.resolve(SOURCE_PATH), "class BookingService {}\n");
        command(repository, "git", "init", "-b", "main");
        command(repository, "git", "add", "--", SOURCE_PATH);
        command(
            repository,
            "git", "-c", "user.name=Test", "-c", "user.email=test@localhost",
            "commit", "-m", "initial"
        );
        baseCommit = command(repository, "git", "rev-parse", "HEAD").trim();
        adapter = new LocalGitPatchWorkspaceAdapter(
            new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.DRAFT_PR,
                repository,
                Duration.ofHours(24)
            ),
            temporaryDirectory.resolve("state").toString()
        );
    }

    @Test
    void appliesOnlyEvidenceScopedSourceAndCommitsTheBoundedDiff() {
        var workspace = adapter.prepare(analysis(), candidate(), hotfixId()).get();
        var proposal = new Proposal(
            "Guard booking response",
            List.of(new FileUpdate(
                SOURCE_PATH,
                "class BookingService { boolean guarded = true; }\n",
                "Guard response"
            ))
        );

        var applied = adapter.apply(workspace, proposal).get();

        assertThat(applied.changes().files()).containsExactly(SOURCE_PATH);
        assertThat(applied.changes().changedLines()).isEqualTo(2);
        assertThat(adapter.currentHead(workspace).get()).isEqualTo(applied.patchCommit());
    }

    @Test
    void rejectsJenkinsfileBeforeWritingIt() {
        var workspace = adapter.prepare(analysis(), candidate(), hotfixId()).get();
        var forbidden = new Proposal(
            "Change pipeline",
            List.of(new FileUpdate("eu/Jenkinsfile", "pipeline {}", "Bypass verification"))
        );

        var result = adapter.apply(workspace, forbidden);

        assertThat(result.isLeft()).isTrue();
        assertThat(Path.of(workspace.worktreePath()).resolve("eu/Jenkinsfile")).doesNotExist();
    }

    @Test
    void acceptsFiveHundredChangedLinesAndRejectsFiveHundredOne() {
        var acceptedWorkspace = adapter.prepare(analysis(), candidate(), hotfixId()).get();
        var accepted = adapter.apply(acceptedWorkspace, proposalWithLines(499));

        assertThat(accepted.get().changes().changedLines()).isEqualTo(500);

        var rejectedWorkspace = adapter.prepare(analysis(), candidate(), hotfixId()).get();
        var rejected = adapter.apply(rejectedWorkspace, proposalWithLines(500));

        assertThat(rejected.isLeft()).isTrue();
    }

    @Test
    void rejectsAnElevenFileProposalBeforeWriting() {
        var files = new LinkedHashMap<String, String>();
        var updates = IntStream.rangeClosed(1, 11)
            .mapToObj(index -> {
                String path = "eu/eu-app/src/main/java/example/File" + index + ".java";
                files.put(path, "class File" + index + " {}\n");
                return new FileUpdate(path, "class File" + index + " { boolean fixed; }\n", "fix");
            })
            .toList();
        var workspace = new Workspace(
            temporaryDirectory.toString(),
            "agent/hotfix/too-many",
            baseCommit,
            files
        );

        var result = adapter.apply(workspace, new Proposal("Too many files", updates));

        assertThat(result.isLeft()).isTrue();
    }

    @Test
    void rejectsEveryConfiguredOperationalOrSensitivePath() {
        List<String> forbiddenPaths = List.of(
            "eu/Jenkinsfile",
            "eu/.env.prod",
            "eu/secret-token.java",
            "eu/certificate.pem",
            "eu/migration/V1.sql",
            "eu/liquibase/changelog.xml",
            "eu/kubernetes/deployment.yaml",
            "eu/helm/values-prod.yaml",
            "eu/app-manifest.yaml",
            "fms-deploy/release.java"
        );

        forbiddenPaths.forEach(path -> {
            var workspace = new Workspace(
                temporaryDirectory.toString(),
                "agent/hotfix/forbidden",
                baseCommit,
                Map.of(path, "original")
            );
            var result = adapter.apply(workspace, new Proposal(
                "Forbidden change",
                List.of(new FileUpdate(path, "changed", "forbidden"))
            ));
            assertThat(result.isLeft()).as(path).isTrue();
        });
    }

    private Proposal proposalWithLines(int lineCount) {
        String content = IntStream.range(0, lineCount)
            .mapToObj(index -> "class BookingService" + index + " {}")
            .reduce((left, right) -> left + '\n' + right)
            .orElse("") + '\n';
        return new Proposal(
            "Boundary change",
            List.of(new FileUpdate(SOURCE_PATH, content, "Boundary test"))
        );
    }

    private AnalysisSession analysis() {
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                new SourceRevision(baseCommit, "main", "test"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate()),
                null
            )
        );
    }

    private BugCandidate candidate() {
        return new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Booking failure",
                "Missing booking guard",
                0.9,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of(SOURCE_PATH + ":1"),
                List.of("test:evidence"),
                List.of()
            ),
            new BugCandidate.Recommendation("Add guard", "Run module tests")
        );
    }

    private String hotfixId() {
        return UUID.randomUUID().toString();
    }

    private String command(Path directory, String... arguments) throws Exception {
        Process process = new ProcessBuilder(List.of(arguments))
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(output).isZero();
        return output;
    }
}
