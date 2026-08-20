package com.example.myagent.command.adapter.out.persistence;

import com.example.myagent.command.application.domain.model.command.CommandIntent;
import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.port.out.CommandFailure;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Adapter
@Component
public class JsonCommandInterpretationPersistenceAdapter implements CommandInterpretationStatePort {
    private static final String FILE_SUFFIX = ".json";

    private final ObjectMapper objectMapper;
    private final Path stateDirectory;

    public JsonCommandInterpretationPersistenceAdapter(
        ObjectMapper objectMapper,
        @Value("${agent.runtime.state-path:.agent/runtime}") String statePath
    ) {
        this.objectMapper = objectMapper;
        this.stateDirectory = Path.of(statePath).resolve("commands").toAbsolutePath().normalize();
    }

    @Override
    public synchronized Either<CommandFailure, CommandInterpretation> save(StateEntry entry) {
        return Try.of(() -> {
            Files.createDirectories(stateDirectory);
            Path target = stateFile(entry.interpretation().metadata().interpretationId());
            writeAtomically(target, StoredEntry.from(entry));
            return entry.interpretation();
        }).toEither().mapLeft(this::storageFailure);
    }

    @Override
    public Either<CommandFailure, Optional<CommandInterpretation>> findById(String interpretationId) {
        return Try.of(() -> {
            Path path = stateFile(interpretationId);
            if (!Files.exists(path)) {
                return Optional.<CommandInterpretation>empty();
            }
            return Optional.of(read(path).toDomain().interpretation());
        }).toEither().mapLeft(this::storageFailure);
    }

    @Override
    @SuppressWarnings("StreamResourceLeak")
    public Either<CommandFailure, Optional<StateEntry>> findByIdempotencyKey(String idempotencyKey) {
        return Try.of(() -> {
            if (!Files.isDirectory(stateDirectory)) {
                return Optional.<StateEntry>empty();
            }
            return Try.withResources(() -> Files.list(stateDirectory))
                .of(paths -> paths.filter(this::isJsonFile)
                    .map(this::read)
                    .filter(entry -> idempotencyKey.equals(entry.idempotencyKey()))
                    .map(StoredEntry::toDomain)
                    .findFirst())
                .get();
        }).toEither().mapLeft(this::storageFailure);
    }

    @Override
    public synchronized Either<CommandFailure, CommandInterpretation> markExecuted(
        String interpretationId
    ) {
        return Try.of(() -> {
            Path path = stateFile(interpretationId);
            StoredEntry storedEntry = read(path);
            StateEntry entry = storedEntry.toDomain();
            CommandInterpretation interpretation = entry.interpretation();
            var decision = new CommandInterpretation.Decision(
                InterpretationStatus.EXECUTED,
                interpretation.decision().command(),
                interpretation.decision().feedback(),
                interpretation.decision().policy(),
                null
            );
            var executed = new CommandInterpretation(interpretation.metadata(), decision);
            writeAtomically(path, StoredEntry.from(new StateEntry(
                entry.idempotencyKey(),
                entry.requestBodyHash(),
                executed
            )));
            return executed;
        }).toEither().mapLeft(this::storageFailure);
    }

    private StoredEntry read(Path path) {
        return Try.of(() -> objectMapper.readValue(path.toFile(), StoredEntry.class)).get();
    }

    private void writeAtomically(Path target, StoredEntry entry) throws IOException {
        Path temporaryFile = Files.createTempFile(stateDirectory, "command-", ".tmp");
        objectMapper.writeValue(temporaryFile.toFile(), entry);
        Try.run(() -> Files.move(
            temporaryFile,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )).recoverWith(
            AtomicMoveNotSupportedException.class,
            exception -> Try.run(() -> Files.move(
                temporaryFile,
                target,
                StandardCopyOption.REPLACE_EXISTING
            ))
        ).get();
    }

    private Path stateFile(String interpretationId) {
        String normalizedId = UUID.fromString(interpretationId).toString();
        return stateDirectory.resolve(normalizedId + FILE_SUFFIX);
    }

    private boolean isJsonFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(FILE_SUFFIX);
    }

    private CommandFailure storageFailure(Throwable throwable) {
        return new CommandFailure("COMMAND_STATE_FAILURE", "자연어 명령 상태를 처리하지 못했습니다.");
    }

    private record StoredEntry(
        String idempotencyKey,
        String requestBodyHash,
        StoredInterpretation interpretation
    ) {
        private static StoredEntry from(StateEntry entry) {
            return new StoredEntry(
                entry.idempotencyKey(),
                entry.requestBodyHash(),
                StoredInterpretation.from(entry.interpretation())
            );
        }

        private StateEntry toDomain() {
            return new StateEntry(idempotencyKey, requestBodyHash, interpretation.toDomain());
        }
    }

    private record StoredInterpretation(
        CommandInterpretation.Metadata metadata,
        StoredDecision decision
    ) {
        private static StoredInterpretation from(CommandInterpretation interpretation) {
            return new StoredInterpretation(
                interpretation.metadata(),
                StoredDecision.from(interpretation.decision())
            );
        }

        private CommandInterpretation toDomain() {
            return new CommandInterpretation(metadata, decision.toDomain());
        }
    }

    private record StoredDecision(
        InterpretationStatus status,
        CommandIntent intent,
        Map<String, String> parameters,
        CommandInterpretation.Feedback feedback,
        CommandInterpretation.PolicyPreview policy,
        String commandHash
    ) {
        private static StoredDecision from(CommandInterpretation.Decision decision) {
            InterpretedCommand command = decision.command();
            return new StoredDecision(
                decision.status(),
                command == null ? null : command.intent(),
                command == null ? Map.of() : encode(command.parameters()),
                decision.feedback(),
                decision.policy(),
                decision.commandHash()
            );
        }

        private CommandInterpretation.Decision toDomain() {
            InterpretedCommand command = intent == null
                ? null : new InterpretedCommand(intent, decode(intent, parameters));
            return new CommandInterpretation.Decision(status, command, feedback, policy, commandHash);
        }

        private static Map<String, String> encode(CommandParameters parameters) {
            var values = new LinkedHashMap<String, String>();
            if (parameters instanceof CommandParameters.JenkinsAnalysis jenkins) {
                values.put("jobPath", jenkins.jobPath());
                values.put("buildNumber", Long.toString(jenkins.buildNumber()));
                encodeSource(values, jenkins.source());
            } else if (parameters instanceof CommandParameters.ObservabilityAnalysis observability) {
                values.put("startAt", observability.startAt().toString());
                values.put("endAt", observability.endAt().toString());
                values.put("environment", observability.environment());
                encodeSource(values, observability.source());
            } else if (parameters instanceof CommandParameters.CandidateList candidateList) {
                values.put("analysisId", candidateList.analysisId());
            } else if (parameters instanceof CommandParameters.CandidateSelection selection) {
                values.put("analysisId", selection.analysisId());
                values.put("analysisVersion", Long.toString(selection.analysisVersion()));
                values.put("candidateId", selection.candidateId());
            } else if (parameters instanceof CommandParameters.HotfixStatus hotfixStatus) {
                values.put("hotfixId", hotfixStatus.hotfixId());
            } else {
                values.put("hotfixId", ((CommandParameters.CiStatusRefresh) parameters).hotfixId());
            }
            return values;
        }

        private static CommandParameters decode(CommandIntent intent, Map<String, String> values) {
            return switch (intent) {
                case ANALYZE_JENKINS -> new CommandParameters.JenkinsAnalysis(
                    values.get("jobPath"), Long.parseLong(values.get("buildNumber")), decodeSource(values)
                );
                case ANALYZE_OBSERVABILITY -> new CommandParameters.ObservabilityAnalysis(
                    Instant.parse(values.get("startAt")),
                    Instant.parse(values.get("endAt")),
                    values.get("environment"),
                    decodeSource(values)
                );
                case LIST_CANDIDATES -> new CommandParameters.CandidateList(values.get("analysisId"));
                case SELECT_CANDIDATE -> new CommandParameters.CandidateSelection(
                    values.get("analysisId"),
                    Long.parseLong(values.get("analysisVersion")),
                    values.get("candidateId")
                );
                case GET_HOTFIX_STATUS -> new CommandParameters.HotfixStatus(values.get("hotfixId"));
                case REFRESH_CI_STATUS -> new CommandParameters.CiStatusRefresh(values.get("hotfixId"));
            };
        }

        private static void encodeSource(Map<String, String> values, SourceReference source) {
            if (source instanceof SourceReference.Branch branch) {
                values.put("sourceType", "BRANCH");
                values.put("branch", branch.name());
            } else {
                values.put("sourceType", "PULL_REQUEST");
                values.put(
                    "pullRequestNumber",
                    Long.toString(((SourceReference.PullRequest) source).number())
                );
            }
        }

        private static SourceReference decodeSource(Map<String, String> values) {
            return "BRANCH".equals(values.get("sourceType"))
                ? new SourceReference.Branch(values.get("branch"))
                : new SourceReference.PullRequest(Long.parseLong(values.get("pullRequestNumber")));
        }
    }
}
