package com.example.myagent.incident.adapter.out.workflow;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.ChangeSummary;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Workspace;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.PatchWorkspacePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".kt", ".kts");

    private final Path repositoryPath;
    private final Path worktreeDirectory;

    public LocalGitPatchWorkspaceAdapter(
        AgentRuntimeProperties runtimeProperties,
        @Value("${agent.runtime.state-path:.agent/runtime}") String statePath
    ) {
        Path configuredRepository = runtimeProperties.fmsRepositoryPath();
        repositoryPath = configuredRepository == null
            ? Path.of(".").toAbsolutePath().normalize()
            : configuredRepository.toAbsolutePath().normalize();
        worktreeDirectory = Path.of(statePath).toAbsolutePath().normalize().resolve("worktrees");
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
            if (!worktree.startsWith(worktreeDirectory) || Files.exists(worktree)) {
                throw new IllegalStateException("Dedicated worktree already exists");
            }
            Files.createDirectories(worktreeDirectory);
            ensureCommitAvailable(analysis.snapshot().sourceRevision().commit());
            runRequired(repositoryPath, List.of(
                "git", "-C", repositoryPath.toString(), "worktree", "add", "--detach",
                worktree.toString(), analysis.snapshot().sourceRevision().commit()
            ));
            String branchName = branchName(hotfixId, candidate.identity().title());
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
            "고정된 소스 커밋으로 전용 hotfix worktree를 준비하지 못했습니다."
        ));
    }

    @Override
    public Either<IncidentFailure, AppliedPatch> apply(Workspace workspace, Proposal proposal) {
        return Try.of(() -> {
            validateProposal(workspace, proposal);
            Path worktree = Path.of(workspace.worktreePath()).toAbsolutePath().normalize();
            proposal.updates().forEach(update -> write(worktree, update.path(), update.content()));
            ChangeSummary changes = inspectChanges(workspace);
            validateChanges(changes);
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
            "수정안이 파일·변경량 정책을 통과하지 못했거나 적용에 실패했습니다."
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
            "재시도를 위해 수정된 소스 파일을 다시 읽지 못했습니다."
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
                "검증 대상 hotfix 커밋을 확인하지 못했습니다."
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

    private void validateChanges(ChangeSummary changes) {
        if (changes.files().isEmpty() || changes.files().size() > MAX_FILES
            || changes.changedLines() > MAX_CHANGED_LINES
            || changes.files().stream().anyMatch(this::isForbidden)) {
            throw new IllegalArgumentException("Applied diff is outside policy");
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
        return normalizeRelative(location.replaceFirst(":\\d+(?::\\d+)?$", ""));
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
            || normalized.contains("migration")
            || normalized.contains("liquibase")
            || normalized.contains("changelog")
            || normalized.endsWith(".sql")
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
        var result = LocalProcessExecutor.run(directory, command);
        if (!result.successful()) {
            throw new IllegalStateException(result.output());
        }
        return result;
    }

    private void ensureCommitAvailable(String commit) throws Exception {
        var existing = LocalProcessExecutor.run(
            repositoryPath,
            List.of("git", "cat-file", "-e", commit + "^{commit}")
        );
        if (!existing.successful()) {
            runRequired(repositoryPath, List.of(
                "git", "fetch", "--no-tags", "origin", commit
            ));
        }
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
}
