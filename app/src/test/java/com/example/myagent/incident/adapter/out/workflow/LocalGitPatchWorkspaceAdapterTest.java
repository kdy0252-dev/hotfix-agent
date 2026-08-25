package com.example.myagent.incident.adapter.out.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.FileUpdate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import java.net.URI;
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
    private Path remoteRepository;
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
        Path bitbucketRoot = temporaryDirectory.resolve("bitbucket");
        remoteRepository = bitbucketRoot.resolve("autocrypt/fms.git");
        Files.createDirectories(remoteRepository.getParent());
        command(temporaryDirectory, "git", "init", "--bare", remoteRepository.toString());
        adapter = new LocalGitPatchWorkspaceAdapter(
            new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.DRAFT_PR,
                repository,
                Duration.ofHours(24)
            ),
            new BitbucketProperties(
                URI.create("https://api.bitbucket.test/2.0"),
                bitbucketRoot.toUri(),
                "autocrypt",
                "fms",
                "token"
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
    void recreatesAnInterruptedWorkspaceFromThePinnedCommit() {
        String hotfixId = hotfixId();
        var interrupted = adapter.prepare(analysis(), candidate(), hotfixId).get();
        adapter.apply(interrupted, new Proposal(
            "Interrupted proposal",
            List.of(new FileUpdate(
                SOURCE_PATH,
                "class BookingService { boolean incomplete = true; }\n",
                "Interrupted change"
            ))
        )).get();

        var recovered = adapter.prepare(analysis(), candidate(), hotfixId).get();

        assertThat(adapter.currentHead(recovered).get()).isEqualTo(baseCommit);
        assertThat(recovered.sourceFiles().get(SOURCE_PATH)).doesNotContain("incomplete");
    }

    @Test
    void mapsAJenkinsWorkspaceLocationToTheEuRepositoryPath() {
        var candidate = candidate(
            "/opt/jenkins-agent/workspace/FMS-EU_PR-1292/" + SOURCE_PATH + ":1"
        );

        var workspace = adapter.prepare(analysis(candidate), candidate, hotfixId()).get();

        assertThat(workspace.sourceFiles()).containsOnlyKeys(SOURCE_PATH);
    }

    @Test
    void keepsARelativeEvidencePathWhenThePackageAlsoContainsEu() throws Exception {
        String relativePath =
            "eu/eu-app/src/test/java/io/autocrypt/fms/eu/IntentionalCompileFailureTest.java";
        Files.createDirectories(repository.resolve(relativePath).getParent());
        Files.writeString(repository.resolve(relativePath), "class IntentionalCompileFailureTest {}\n");
        command(repository, "git", "add", "--", relativePath);
        command(
            repository,
            "git", "-c", "user.name=Test", "-c", "user.email=test@localhost",
            "commit", "-m", "add relative evidence path"
        );
        baseCommit = command(repository, "git", "rev-parse", "HEAD").trim();
        var candidate = candidate(relativePath + ":5");

        var workspace = adapter.prepare(analysis(candidate), candidate, hotfixId()).get();

        assertThat(workspace.sourceFiles()).containsOnlyKeys(relativePath);
    }

    @Test
    void fetchesAMissingCommitWithTokenRemoteInsteadOfSshOrigin() throws Exception {
        command(repository, "git", "push", remoteRepository.toString(), "main");
        command(repository, "git", "remote", "add", "origin", "git@bitbucket.invalid:fms.git");
        Path publisher = temporaryDirectory.resolve("publisher");
        command(
            temporaryDirectory,
            "git", "clone", "--branch", "main", remoteRepository.toString(), publisher.toString()
        );
        Files.writeString(publisher.resolve(SOURCE_PATH), "class BookingService { boolean fixed; }\n");
        command(publisher, "git", "add", "--", SOURCE_PATH);
        command(
            publisher,
            "git", "-c", "user.name=Test", "-c", "user.email=test@localhost",
            "commit", "-m", "remote-only"
        );
        command(publisher, "git", "push", "origin", "main");
        String remoteCommit = command(publisher, "git", "rev-parse", "HEAD").trim();

        var workspace = adapter.prepare(
            analysis(candidate(), remoteCommit),
            candidate(),
            hotfixId()
        ).get();

        assertThat(workspace.baseCommit()).isEqualTo(remoteCommit);
        assertThat(workspace.sourceFiles().get(SOURCE_PATH)).contains("boolean fixed");
    }

    @Test
    void reloadsAHumanCommitFromThePublishedReviewBranch() throws Exception {
        String hotfixId = hotfixId();
        var workspace = adapter.prepare(analysis(), candidate(), hotfixId).get();
        var applied = adapter.apply(workspace, new Proposal(
            "Agent proposal",
            List.of(new FileUpdate(
                SOURCE_PATH,
                "class BookingService { boolean proposed = true; }\n",
                "Agent proposal"
            ))
        )).get();

        var reviewBranch = adapter.publishForHumanReview(
            analysis(), candidate(), hotfixId, applied.workspace().branchName()
        ).get();
        Path reviewer = temporaryDirectory.resolve("reviewer");
        command(temporaryDirectory, "git", "clone", remoteRepository.toString(), reviewer.toString());
        command(reviewer, "git", "switch", reviewBranch.name());
        Files.writeString(
            reviewer.resolve(SOURCE_PATH),
            "class BookingService { boolean humanReviewed = true; }\n"
        );
        command(reviewer, "git", "add", "--", SOURCE_PATH);
        command(
            reviewer,
            "git", "-c", "user.name=Reviewer", "-c", "user.email=reviewer@localhost",
            "commit", "-m", "fix: human review"
        );
        command(reviewer, "git", "push", "origin", reviewBranch.name());
        String reviewerCommit = command(reviewer, "git", "rev-parse", "HEAD").trim();

        var reloaded = adapter.reloadHumanChanges(
            analysis(), candidate(), hotfixId, reviewBranch.name()
        ).get();

        assertThat(reloaded.patchCommit()).isEqualTo(reviewerCommit);
        assertThat(reloaded.workspace().sourceFiles().get(SOURCE_PATH))
            .contains("humanReviewed");
        assertThat(reloaded.changes().files()).containsExactly(SOURCE_PATH);
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

    @Test
    void acceptsAnAdditiveBackwardCompatibleMigration() throws Exception {
        String migrationPath = addMigrationFile();
        var migrationCandidate = candidate(migrationPath + ":2");
        var workspace = adapter.prepare(
            analysis(migrationCandidate),
            migrationCandidate,
            hotfixId()
        ).get();
        var proposal = new Proposal(
            "Add nullable booking note",
            List.of(new FileUpdate(
                migrationPath,
                """
                    <databaseChangeLog>
                        <changeSet id="add-booking-note" author="agent">
                            <addColumn tableName="bookings">
                                <column name="note" type="varchar(255)"/>
                            </addColumn>
                        </changeSet>
                    </databaseChangeLog>
                    """,
                "Add a nullable column"
            ))
        );

        var result = adapter.apply(workspace, proposal);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().changes().files()).containsExactly(migrationPath);
    }

    @Test
    void rejectsABackwardIncompatibleMigration() throws Exception {
        String migrationPath = addMigrationFile();
        var migrationCandidate = candidate(migrationPath + ":2");
        var workspace = adapter.prepare(
            analysis(migrationCandidate),
            migrationCandidate,
            hotfixId()
        ).get();
        var proposal = new Proposal(
            "Drop legacy booking column",
            List.of(new FileUpdate(
                migrationPath,
                """
                    <databaseChangeLog>
                        <changeSet id="drop-legacy" author="agent">
                            <dropColumn tableName="bookings" columnName="legacy"/>
                        </changeSet>
                    </databaseChangeLog>
                    """,
                "Drop a column"
            ))
        );

        assertThat(adapter.apply(workspace, proposal).isLeft()).isTrue();
    }

    private String addMigrationFile() throws Exception {
        String migrationPath =
            "eu/eu-app/src/main/resources/db/changelog/changes/2026-08.xml";
        Files.createDirectories(repository.resolve(migrationPath).getParent());
        Files.writeString(repository.resolve(migrationPath), """
            <databaseChangeLog>
            </databaseChangeLog>
            """);
        command(repository, "git", "add", "--", migrationPath);
        command(
            repository,
            "git", "-c", "user.name=Test", "-c", "user.email=test@localhost",
            "commit", "-m", "add migration"
        );
        baseCommit = command(repository, "git", "rev-parse", "HEAD").trim();
        return migrationPath;
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
        return analysis(candidate());
    }

    private AnalysisSession analysis(BugCandidate candidate) {
        return analysis(candidate, baseCommit);
    }

    private AnalysisSession analysis(BugCandidate candidate, String sourceCommit) {
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                new SourceRevision(sourceCommit, "main", "test"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate),
                null
            )
        );
    }

    private BugCandidate candidate() {
        return candidate(SOURCE_PATH + ":1");
    }

    private BugCandidate candidate(String sourceLocation) {
        return new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Booking failure",
                "Missing booking guard",
                0.9,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of(sourceLocation),
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
