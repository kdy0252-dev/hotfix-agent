package com.example.myagent.incident.adapter.out.workflow;

import com.example.myagent.global.configuration.ParityProfileProperties;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.JenkinsfileProfile;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.StageResult;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.VerificationProvenance;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.VerificationPort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class LocalJenkinsParityVerificationAdapter implements VerificationPort {
    private static final Set<String> APP_TASKS = Set.of(
        ":eu:eu-app:architectureTest", ":eu:eu-app:checkstyleMain", ":eu:eu-app:test"
    );
    private static final Set<String> GATEWAY_TASKS = Set.of(
        ":eu:eu-gateway:checkstyleMain", ":eu:eu-gateway:test"
    );
    private static final Set<String> METRICS_TASKS = Set.of(
        ":eu:eu-metrics:architectureTest", ":eu:eu-metrics:checkstyleMain",
        ":eu:eu-metrics:test"
    );

    private final ParityProfileProperties properties;

    public LocalJenkinsParityVerificationAdapter(ParityProfileProperties properties) {
        this.properties = properties;
    }

    @Override
    public Either<IncidentFailure, Verification> runFocused(AppliedPatch patch, int attempt) {
        return Try.of(() -> {
            Path worktree = Path.of(patch.workspace().worktreePath());
            List<String> tasks = focusedTasks(patch.changes().files());
            var command = new ArrayList<>(List.of(
                "./gradlew", "--parallel",
                "--max-workers=" + properties.limits().maxWorkers()
            ));
            command.addAll(tasks);
            var result = LocalProcessExecutor.run(worktree, command);
            return Verification.focused(
                attempt,
                patch.workspace().baseCommit(),
                patch.patchCommit(),
                List.of(new StageResult("focused-gradle", result.exitCode(), true))
            );
        }).toEither().mapLeft(exception -> failure(
            "FOCUSED_VERIFICATION_FAILED",
            "변경 모듈의 집중 검증을 실행하지 못했습니다."
        ));
    }

    @Override
    public Either<IncidentFailure, Verification> runParity(
        AppliedPatch patch,
        int focusedAttempts
    ) {
        return Try.of(() -> {
            Path worktree = Path.of(patch.workspace().worktreePath());
            String jenkinsfileHash = sha256(worktree.resolve(JenkinsParityProfile.JENKINSFILE));
            Integer profileVersion = properties.approvedProfiles().get(jenkinsfileHash);
            if (profileVersion == null) {
                throw new UnapprovedParityProfileException();
            }
            List<StageResult> stages = new ArrayList<>();
            runStage(
                worktree,
                JenkinsParityProfile.GRADLE_STAGE,
                JenkinsParityProfile.gradleCommand(properties.limits().maxWorkers()),
                Map.of(),
                stages
            );
            runStage(
                worktree,
                JenkinsParityProfile.COVERAGE_STAGE,
                List.of(
                    "sh", "eu/ci/print-jacoco-coverage.sh",
                    "eu/eu-app/build/reports/jacoco/test/jacocoTestReport.xml",
                    "eu/eu-app/build/reports/jacoco/integration-test/jacocoIntegrationTestReport.xml",
                    "eu/eu-app/build/reports/jacoco/test-and-integration-test/"
                        + "jacocoTestAndIntegrationTestReport.xml"
                ),
                Map.of(),
                stages
            );
            String runtimeTag = patch.patchCommit().substring(0, 12) + "-agent";
            runStage(
                worktree,
                JenkinsParityProfile.IMAGE_STAGE,
                List.of(
                    "./gradlew", "--no-configuration-cache", "--parallel",
                    "--max-workers=" + properties.limits().maxWorkers(),
                    ":eu:eu-app:jibDockerBuild", ":eu:eu-gateway:jibDockerBuild",
                    ":eu:eu-metrics:jibDockerBuild"
                ),
                Map.of(
                    "JIB_PLATFORM_ARCH", "amd64",
                    "APP_IMAGE", "eu-app:" + runtimeTag,
                    "GATEWAY_IMAGE", "eu-gateway:" + runtimeTag,
                    "METRICS_IMAGE", "eu-metrics:" + runtimeTag
                ),
                stages
            );
            runStage(
                worktree,
                JenkinsParityProfile.INTEGRATION_STAGE,
                List.of("sh", "eu/ci/run-integration-tests.sh"),
                integrationEnvironment(worktree, patch.patchCommit(), runtimeTag),
                stages
            );
            return new Verification(
                focusedAttempts,
                new VerificationProvenance(
                    patch.workspace().baseCommit(),
                    patch.patchCommit(),
                    new JenkinsfileProfile(
                        JenkinsParityProfile.JENKINSFILE,
                        jenkinsfileHash,
                        profileVersion
                    )
                ),
                stages
            );
        }).toEither().mapLeft(exception -> exception instanceof UnapprovedParityProfileException
            ? failure(
                "JENKINS_PARITY_PROFILE_NOT_APPROVED",
                "현재 Jenkinsfile 해시와 일치하는 승인된 parity profile이 없습니다."
            )
            : failure(
                "JENKINS_PARITY_EXECUTION_FAILED",
                "Jenkins 동등성 검증을 실행하지 못했습니다."
            ));
    }

    private Map<String, String> integrationEnvironment(
        Path worktree,
        String patchCommit,
        String runtimeTag
    ) {
        String projectPrefix = "fms-hotfix-" + patchCommit.substring(0, 8);
        Path newmanWorkspace = properties.newmanWorkspaceRoot()
            .resolve("worktrees")
            .resolve(worktree.getFileName());
        return Map.ofEntries(
            Map.entry("APP_IMAGE", "eu-app:" + runtimeTag),
            Map.entry("GATEWAY_IMAGE", "eu-gateway:" + runtimeTag),
            Map.entry("METRICS_IMAGE", "eu-metrics:" + runtimeTag),
            Map.entry("COMPOSE_PROJECT_PREFIX", projectPrefix),
            Map.entry("COMPOSE_PROJECT_NAME", projectPrefix + "-run"),
            Map.entry(
                "COMPOSE_FILE_PATH",
                newmanWorkspace.resolve("eu/compose.yml").toString()
            ),
            Map.entry(
                "COMPOSE_CI_FILE_PATH",
                newmanWorkspace.resolve("eu/compose-ci.yml").toString()
            ),
            Map.entry("NEWMAN_REPORT_DIR", "build/newman"),
            Map.entry("CI_RESOURCE_CREATED_AT", Long.toString(Instant.now().getEpochSecond())),
            Map.entry("WORKSPACE", newmanWorkspace.toString()),
            Map.entry("CI_UID", "0"),
            Map.entry("CI_GID", "0")
        );
    }

    private void runStage(
        Path worktree,
        String name,
        List<String> command,
        Map<String, String> environment,
        List<StageResult> stages
    ) throws Exception {
        if (stages.stream().anyMatch(stage -> stage.required() && stage.exitCode() != 0)) {
            return;
        }
        var result = LocalProcessExecutor.run(
            worktree,
            command,
            environment,
            Duration.ofMinutes(30)
        );
        stages.add(new StageResult(name, result.exitCode(), true));
    }

    private List<String> focusedTasks(List<String> changedFiles) {
        var tasks = new LinkedHashSet<String>();
        changedFiles.forEach(file -> {
            if (file.startsWith("eu/eu-app/")) {
                tasks.addAll(APP_TASKS);
            } else if (file.startsWith("eu/eu-gateway/")) {
                tasks.addAll(GATEWAY_TASKS);
            } else if (file.startsWith("eu/eu-metrics/")) {
                tasks.addAll(METRICS_TASKS);
            }
        });
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("No approved focused verification task");
        }
        return List.copyOf(tasks);
    }

    private String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest);
    }

    private IncidentFailure failure(String code, String message) {
        return new IncidentFailure(code, message);
    }

    private static final class UnapprovedParityProfileException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
