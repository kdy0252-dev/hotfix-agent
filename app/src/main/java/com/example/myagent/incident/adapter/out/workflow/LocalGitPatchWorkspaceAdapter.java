package com.example.myagent.incident.adapter.out.workflow;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.ChangeSummary;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import com.example.myagent.incident.application.domain.model.policy.MigrationSafetyPolicy;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.PatchWorkspacePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class LocalGitPatchWorkspaceAdapter implements PatchWorkspacePort {
    private static final int MAX_FILES = 10;
    private static final int MAX_CHANGED_LINES = 500;
    private static final int MAX_SOURCE_CHARACTERS = 60_000;
    private static final int MAX_SOURCE_CONTEXT_CHARACTERS = 200_000;
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
        ".java", ".kt", ".kts", ".sql", ".xml", ".yaml", ".yml"
    );
    private static final Set<PosixFilePermission> OWNER_EXECUTABLE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );

    private final Path repositoryPath;
    private final Path worktreeDirectory;
    private final BitbucketProperties bitbucket;

    public LocalGitPatchWorkspaceAdapter(
        AgentRuntimeProperties runtimeProperties,
        BitbucketProperties bitbucket,
        @Value("${agent.runtime.state-path:.agent/runtime}") String statePath
    ) {
        Path configuredRepository = runtimeProperties.fmsRepositoryPath();
        repositoryPath = configuredRepository == null
            ? Path.of(".").toAbsolutePath().normalize()
            : configuredRepository.toAbsolutePath().normalize();
        worktreeDirectory = Path.of(statePath).toAbsolutePath().normalize().resolve("worktrees");
        this.bitbucket = bitbucket;
    }

    @Override
    public Either<IncidentFailure, Workspace> prepare(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId
    ) {
        return Try.of(() -> {
            String normalizedId = UUID.fromString(hotfixId).toString();
            Path worktree = worktreeDirectory.resolve(normalizedId).normalize();
            if (!worktree.startsWith(worktreeDirectory)) {
                throw new IllegalStateException("Dedicated worktree path is invalid");
            }
            String branchName = branchName(hotfixId, candidate.identity().title());
            Files.createDirectories(worktreeDirectory);
            resetInterruptedWorkspace(worktree, branchName);
            ensureCommitAvailable(analysis.snapshot().sourceRevision().commit());
            runRequired(repositoryPath, List.of(
                "git", "-C", repositoryPath.toString(), "worktree", "add", "--detach",
                worktree.toString(), analysis.snapshot().sourceRevision().commit()
            ));
            runRequired(worktree, List.of("git", "switch", "-c", branchName));
            Map<String, String> sourceFiles = loadSourceFiles(worktree, candidate);
            if (sourceFiles.isEmpty()) {
                throw new IllegalStateException("No eligible source file was found in evidence");
            }
            return new Workspace(
                worktree.toString(),
                branchName,
                analysis.snapshot().sourceRevision().commit(),
                sourceFiles
            );
        }).toEither().mapLeft(exception -> failure(
            "PATCH_WORKSPACE_PREPARE_FAILED",
            "고정된 소스 커밋으로 전용 hotfix worktree를 준비하지 못했습니다.",
            exception
        ));
    }

    @Override
    public Either<IncidentFailure, AppliedPatch> apply(Workspace workspace, Proposal proposal) {
        return Try.of(() -> {
            validateProposal(workspace, proposal);
            Path worktree = Path.of(workspace.worktreePath()).toAbsolutePath().normalize();
            proposal.updates().forEach(update -> write(worktree, update.path(), update.content()));
            ChangeSummary changes = inspectChanges(workspace);
            validateChanges(workspace, changes);
            runRequired(worktree, addCommand(changes.files()));
            runRequired(worktree, List.of(
                "git", "-c", "user.name=FMS Hotfix Agent",
                "-c", "user.email=fms-hotfix-agent@localhost",
                "commit", "-m", "fix(agent): " + safeSummary(proposal.summary())
            ));
            Workspace refreshed = new Workspace(
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                readAllowedFiles(worktree, workspace.sourceFiles().keySet())
            );
            return new AppliedPatch(refreshed, changes, readHead(worktree));
        }).toEither().mapLeft(exception -> failure(
            "PATCH_POLICY_OR_APPLY_FAILED",
            "수정안이 파일·변경량 정책을 통과하지 못했거나 적용에 실패했습니다.",
            exception
        ));
    }

    @Override
    public Either<IncidentFailure, Workspace> refresh(Workspace workspace) {
        return Try.of(() -> new Workspace(
            workspace.worktreePath(),
            workspace.branchName(),
            workspace.baseCommit(),
            readAllowedFiles(Path.of(workspace.worktreePath()), workspace.sourceFiles().keySet())
        )).toEither().mapLeft(exception -> failure(
            "PATCH_WORKSPACE_REFRESH_FAILED",
            "재시도를 위해 수정된 소스 파일을 다시 읽지 못했습니다.",
            exception
        ));
    }

    @Override
    public Either<IncidentFailure, String> currentHead(Workspace workspace) {
        return Try.of(() -> {
            Path worktree = Path.of(workspace.worktreePath());
            requireCleanTrackedFiles(worktree);
            return readHead(worktree);
        })
            .toEither()
            .mapLeft(exception -> failure(
                "PATCH_HEAD_READ_FAILED",
                "검증 대상 hotfix 커밋을 확인하지 못했습니다.",
                exception
            ));
    }

    @Override
    public Either<IncidentFailure, ReviewBranch> publishForHumanReview(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        String branchName
    ) {
        return Try.of(() -> {
            Workspace workspace = restoreWorkspace(analysis, candidate, hotfixId, branchName);
            pushBranch(workspace);
            return new ReviewBranch(
                branchName,
                branchUrl(branchName),
                readHead(Path.of(workspace.worktreePath()))
            );
        }).toEither().mapLeft(exception -> failure(
            "HUMAN_REVIEW_BRANCH_PUBLISH_FAILED",
            "사람 검토용 hotfix branch를 Bitbucket에 게시하지 못했습니다.",
            exception
        ));
    }

    @Override
    public Either<IncidentFailure, AppliedPatch> reloadHumanChanges(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        String branchName
    ) {
        return Try.of(() -> {
            Workspace workspace = restoreWorkspace(analysis, candidate, hotfixId, branchName);
            Path worktree = Path.of(workspace.worktreePath());
            fetchBranch(worktree, branchName);
            runRequired(worktree, List.of("git", "reset", "--hard", "FETCH_HEAD"));
            runRequired(worktree, List.of(
                "git", "merge-base", "--is-ancestor", workspace.baseCommit(), "HEAD"
            ));
            ChangeSummary changes = inspectChanges(workspace);
            validateChanges(workspace, changes);
            Workspace refreshed = new Workspace(
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                loadSourceFiles(worktree, candidate)
            );
            return new AppliedPatch(refreshed, changes, readHead(worktree));
        }).toEither().mapLeft(exception -> failure(
            "HUMAN_CHANGES_RELOAD_FAILED",
            "사람이 push한 commit을 불러오지 못했거나 변경 정책을 통과하지 못했습니다.",
            exception
        ));
    }

    private Map<String, String> loadSourceFiles(Path worktree, BugCandidate candidate)
        throws Exception {
        var allowedPaths = new LinkedHashSet<String>();
        candidate.evidence().sourceLocations().stream()
            .map(this::pathFromLocation)
            .filter(this::isSourcePath)
            .filter(path -> !isForbidden(path))
            .limit(MAX_FILES)
            .forEach(path -> {
                allowedPaths.add(path);
                correspondingTestPath(path).ifPresent(allowedPaths::add);
            });
        if (allowedPaths.size() > MAX_FILES) {
            throw new IllegalArgumentException("Evidence scope exceeds file policy");
        }
        return readAllowedFiles(worktree, allowedPaths);
    }

    private Map<String, String> readAllowedFiles(Path worktree, Set<String> paths) throws Exception {
        var files = new LinkedHashMap<String, String>();
        for (String relativePath : paths) {
            Path path = resolve(worktree, relativePath);
            if (Files.isRegularFile(path)) {
                String content = Files.readString(path);
                if (content.length() > MAX_SOURCE_CHARACTERS) {
                    throw new IllegalArgumentException("Source file is too large");
                }
                int currentCharacters = files.values().stream().mapToInt(String::length).sum();
                if (currentCharacters + content.length() > MAX_SOURCE_CONTEXT_CHARACTERS) {
                    throw new IllegalArgumentException("Source context is too large");
                }
                files.put(relativePath, content);
            }
        }
        return files;
    }

    private void validateProposal(Workspace workspace, Proposal proposal) {
        if (proposal.updates().isEmpty() || proposal.updates().size() > MAX_FILES) {
            throw new IllegalArgumentException("Patch file count is outside policy");
        }
        var distinctPaths = new LinkedHashSet<String>();
        proposal.updates().forEach(update -> {
            String path = normalizeRelative(update.path());
            if (!workspace.sourceFiles().containsKey(path) || isForbidden(path)) {
                throw new IllegalArgumentException("Patch path is outside evidence scope");
            }
            if (update.content() == null || !distinctPaths.add(path)) {
                throw new IllegalArgumentException("Patch update is invalid or duplicated");
            }
        });
    }

    private ChangeSummary inspectChanges(Workspace workspace) throws Exception {
        Path worktree = Path.of(workspace.worktreePath());
        var names = runRequired(worktree, List.of(
            "git", "diff", "--name-only", workspace.baseCommit()
        )).output().lines().filter(line -> !line.isBlank()).toList();
        int changedLines = runRequired(worktree, List.of(
            "git", "diff", "--numstat", workspace.baseCommit()
        )).output().lines().mapToInt(this::changedLines).sum();
        return new ChangeSummary(names, changedLines);
    }

    private void validateChanges(Workspace workspace, ChangeSummary changes) throws Exception {
        if (changes.files().isEmpty() || changes.files().size() > MAX_FILES
            || changes.changedLines() > MAX_CHANGED_LINES
            || changes.files().stream().anyMatch(this::isForbidden)
            || !workspace.sourceFiles().keySet().containsAll(changes.files())) {
            throw new IllegalArgumentException("Applied diff is outside policy");
        }
        validateMigrationChanges(workspace, changes.files());
    }

    private void validateMigrationChanges(Workspace workspace, List<String> files)
        throws Exception {
        var migrationFiles = files.stream()
            .filter(MigrationSafetyPolicy::isMigrationPath)
            .toList();
        if (migrationFiles.isEmpty()) {
            return;
        }
        var command = new ArrayList<>(List.of(
            "git", "diff", "--unified=0", workspace.baseCommit(), "--"
        ));
        command.addAll(migrationFiles);
        String diff = runRequired(Path.of(workspace.worktreePath()), command).output();
        if (!MigrationSafetyPolicy.isBackwardCompatibleDiff(diff)) {
            throw new IllegalArgumentException(
                "Migration diff is destructive or backward incompatible"
            );
        }
    }

    private List<String> addCommand(List<String> files) {
        var command = new ArrayList<>(List.of("git", "add", "--"));
        command.addAll(files);
        return command;
    }

    private void write(Path worktree, String relativePath, String content) {
        Try.run(() -> Files.writeString(resolve(worktree, relativePath), content))
            .getOrElseThrow(exception -> new IllegalStateException(
                "Unable to write proposed source file",
                exception
            ));
    }

    private Path resolve(Path worktree, String relativePath) {
        Path normalizedWorktree = worktree.toAbsolutePath().normalize();
        Path resolved = normalizedWorktree.resolve(normalizeRelative(relativePath)).normalize();
        if (!resolved.startsWith(normalizedWorktree)) {
            throw new IllegalArgumentException("Patch path escapes worktree");
        }
        return resolved;
    }

    private String normalizeRelative(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Patch path is required");
        }
        return Path.of(path).normalize().toString().replace('\\', '/');
    }

    private String pathFromLocation(String location) {
        if (location == null) {
            return "";
        }
        String path = location.replaceFirst(":\\d+(?::\\d+)?$", "").replace('\\', '/');
        int euRoot = path.indexOf("/eu/");
        if (euRoot >= 0) {
            return normalizeRelative(path.substring(euRoot + 1));
        }
        return Path.of(path).isAbsolute() ? "" : normalizeRelative(path);
    }

    private boolean isSourcePath(String path) {
        return SOURCE_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    private Optional<String> correspondingTestPath(String sourcePath) {
        if (!sourcePath.contains("/src/main/")) {
            return Optional.empty();
        }
        String candidate = sourcePath.replace("/src/main/", "/src/test/");
        return Optional.of(candidate);
    }

    private boolean isForbidden(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.equals("jenkinsfile")
            || normalized.endsWith("/jenkinsfile")
            || normalized.contains("/.env")
            || normalized.startsWith(".env")
            || normalized.contains("secret")
            || normalized.endsWith(".key")
            || normalized.endsWith(".pem")
            || normalized.endsWith(".p12")
            || normalized.contains("kubernetes")
            || normalized.contains("/k8s/")
            || normalized.contains("/helm/")
            || normalized.contains("manifest")
            || normalized.contains("fms-deploy")
            || normalized.matches("(^|.*/)values[^/]*\\.ya?ml$");
    }

    private int changedLines(String numstatLine) {
        int firstTab = numstatLine.indexOf('\t');
        int secondTab = numstatLine.indexOf('\t', firstTab + 1);
        if (firstTab < 0 || secondTab < 0) {
            return MAX_CHANGED_LINES + 1;
        }
        String added = numstatLine.substring(0, firstTab);
        String deleted = numstatLine.substring(firstTab + 1, secondTab);
        if ("-".equals(added) || "-".equals(deleted)) {
            return MAX_CHANGED_LINES + 1;
        }
        return Integer.parseInt(added) + Integer.parseInt(deleted);
    }

    private LocalProcessExecutor.Result runRequired(Path directory, List<String> command)
        throws Exception {
        return runRequired(directory, command, Map.of());
    }

    private LocalProcessExecutor.Result runRequired(
        Path directory,
        List<String> command,
        Map<String, String> environment
    ) throws Exception {
        var result = LocalProcessExecutor.run(
            directory,
            command,
            environment,
            Duration.ofMinutes(5)
        );
        if (!result.successful()) {
            throw new IllegalStateException(result.output());
        }
        return result;
    }

    private Workspace restoreWorkspace(
        AnalysisSession analysis,
        BugCandidate candidate,
        String hotfixId,
        String branchName
    ) throws Exception {
        String normalizedId = UUID.fromString(hotfixId).toString();
        Path worktree = worktreeDirectory.resolve(normalizedId).toAbsolutePath().normalize();
        if (!worktree.startsWith(worktreeDirectory) || !Files.isDirectory(worktree)) {
            throw new IllegalStateException("Dedicated hotfix worktree does not exist");
        }
        if (branchName == null || !branchName.startsWith("agent/hotfix/")) {
            throw new IllegalArgumentException("Only agent hotfix branches may be resumed");
        }
        String actualBranch = runRequired(
            worktree,
            List.of("git", "branch", "--show-current")
        ).output().trim();
        if (!branchName.equals(actualBranch)) {
            throw new IllegalStateException("Hotfix worktree branch does not match stored state");
        }
        return new Workspace(
            worktree.toString(),
            branchName,
            analysis.snapshot().sourceRevision().commit(),
            loadSourceFiles(worktree, candidate)
        );
    }

    private void pushBranch(Workspace workspace) throws Exception {
        withBitbucketAuthentication(environment -> runRequired(
            Path.of(workspace.worktreePath()),
            List.of("git", "push", "--set-upstream", gitRemote(), workspace.branchName()),
            environment
        ));
    }

    private void fetchBranch(Path worktree, String branchName) throws Exception {
        withBitbucketAuthentication(environment -> runRequired(
            worktree,
            List.of("git", "fetch", "--no-tags", gitRemote(), branchName),
            environment
        ));
    }

    private void withBitbucketAuthentication(AuthenticatedGitAction action) throws Exception {
        Path askPass = Files.createTempFile("agent-bitbucket-askpass-", ".sh");
        Try.run(() -> {
            Files.writeString(askPass, """
                #!/bin/sh
                case "$1" in
                  *Username*) printf '%s\\n' 'x-token-auth' ;;
                  *) printf '%s\\n' "$BITBUCKET_TOKEN" ;;
                esac
                """);
            Files.setPosixFilePermissions(askPass, OWNER_EXECUTABLE);
            action.run(Map.of(
                "GIT_ASKPASS", askPass.toString(),
                "GIT_TERMINAL_PROMPT", "0",
                "BITBUCKET_TOKEN", bitbucket.token()
            ));
        }).andFinally(() -> delete(askPass)).get();
    }

    private void delete(Path path) {
        Try.run(() -> Files.deleteIfExists(path)).get();
    }

    private String gitRemote() {
        String base = bitbucket.gitBaseUrl().toString().replaceAll("/$", "");
        return base + '/' + bitbucket.workspace() + '/' + bitbucket.repository() + ".git";
    }

    private String branchUrl(String branchName) {
        String base = bitbucket.gitBaseUrl().toString().replaceAll("/$", "");
        String encodedBranch = URLEncoder.encode(branchName, StandardCharsets.UTF_8)
            .replace("+", "%20");
        return base + '/' + bitbucket.workspace() + '/' + bitbucket.repository()
            + "/branch/" + encodedBranch;
    }

    private void ensureCommitAvailable(String commit) throws Exception {
        var existing = LocalProcessExecutor.run(
            repositoryPath,
            List.of("git", "cat-file", "-e", commit + "^{commit}")
        );
        if (!existing.successful()) {
            withBitbucketAuthentication(environment -> runRequired(
                repositoryPath,
                List.of("git", "fetch", "--no-tags", gitRemote(), commit),
                environment
            ));
        }
    }

    private void resetInterruptedWorkspace(Path worktree, String branchName) throws Exception {
        if (Files.exists(worktree)) {
            var removal = LocalProcessExecutor.run(
                repositoryPath,
                List.of("git", "worktree", "remove", "--force", worktree.toString())
            );
            if (!removal.successful() && Files.exists(worktree)) {
                deleteRecursively(worktree);
            }
        }
        LocalProcessExecutor.run(repositoryPath, List.of("git", "worktree", "prune"));
        LocalProcessExecutor.run(repositoryPath, List.of("git", "branch", "-D", branchName));
    }

    @SuppressWarnings("StreamResourceLeak")
    private void deleteRecursively(Path path) {
        Try.withResources(() -> Files.walk(path))
            .of(paths -> {
                paths.sorted(Comparator.reverseOrder())
                    .forEach(item -> Try.run(() -> Files.deleteIfExists(item)).get());
                return null;
            })
            .get();
    }

    private String readHead(Path worktree) throws Exception {
        return runRequired(worktree, List.of("git", "rev-parse", "HEAD")).output().trim();
    }

    private void requireCleanTrackedFiles(Path worktree) throws Exception {
        runRequired(worktree, List.of("git", "diff", "--quiet"));
        runRequired(worktree, List.of("git", "diff", "--cached", "--quiet"));
    }

    private String branchName(String hotfixId, String title) {
        String slug = title.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        String safeSlug = slug.isBlank() ? "candidate" : slug.substring(0, Math.min(40, slug.length()));
        return "agent/hotfix/" + hotfixId.substring(0, 8) + '-' + safeSlug;
    }

    private String safeSummary(String summary) {
        String value = summary == null ? "selected incident" : summary.replaceAll("[\\r\\n]+", " ");
        return value.substring(0, Math.min(value.length(), 72));
    }

    private IncidentFailure failure(String code, String message) {
        return new IncidentFailure(code, message);
    }

    private IncidentFailure failure(String code, String message, Throwable exception) {
        String token = Optional.ofNullable(bitbucket.token()).orElse("");
        String technicalMessage = Optional.ofNullable(exception.getMessage())
            .orElse(exception.getClass().getSimpleName());
        if (!token.isBlank()) {
            technicalMessage = technicalMessage.replace(token, "[REDACTED]");
        }
        String normalized = technicalMessage.replaceAll("[\\r\\n]+", " ").trim();
        String bounded = normalized.substring(0, Math.min(normalized.length(), 800));
        return failure(code, message + " 상세: " + bounded);
    }

    @FunctionalInterface
    private interface AuthenticatedGitAction {
        void run(Map<String, String> environment) throws Exception;
    }
}
