package com.example.myagent.command.adapter.out.persistence;

import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.port.out.CommandExecutionStatePort;
import com.example.myagent.command.application.port.out.CommandFailure;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Adapter
@Component
public class JsonCommandExecutionPersistenceAdapter implements CommandExecutionStatePort {
    private final ObjectMapper objectMapper;
    private final Path executionDirectory;

    public JsonCommandExecutionPersistenceAdapter(
        ObjectMapper objectMapper,
        @Value("${agent.runtime.state-path:.agent/runtime}") String statePath
    ) {
        this.objectMapper = objectMapper;
        this.executionDirectory = Path.of(statePath)
            .resolve("commands/executions")
            .toAbsolutePath()
            .normalize();
    }

    @Override
    public synchronized Either<CommandFailure, CommandExecution> save(CommandExecution execution) {
        return Try.of(() -> {
            Files.createDirectories(executionDirectory);
            Path target = executionDirectory.resolve(execution.identity().executionId() + ".json");
            Path temporaryFile = Files.createTempFile(executionDirectory, "execution-", ".tmp");
            objectMapper.writeValue(temporaryFile.toFile(), execution);
            move(temporaryFile, target);
            return execution;
        }).toEither().mapLeft(this::failure);
    }

    @Override
    @SuppressWarnings("StreamResourceLeak")
    public Either<CommandFailure, Optional<CommandExecution>> findByIdempotencyKey(String key) {
        return Try.of(() -> {
            if (!Files.isDirectory(executionDirectory)) {
                return Optional.<CommandExecution>empty();
            }
            return Try.withResources(() -> Files.list(executionDirectory))
                .of(paths -> paths.filter(this::isJsonFile)
                    .map(this::read)
                    .filter(execution -> key.equals(execution.identity().idempotencyKey()))
                    .findFirst())
                .get();
        }).toEither().mapLeft(this::failure);
    }

    private CommandExecution read(Path path) {
        return objectMapper.readValue(path.toFile(), CommandExecution.class);
    }

    private void move(Path source, Path target) throws IOException {
        Try.run(() -> Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )).recoverWith(
            AtomicMoveNotSupportedException.class,
            exception -> Try.run(() -> Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING
            ))
        ).get();
    }

    private boolean isJsonFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json");
    }

    private CommandFailure failure(Throwable throwable) {
        return new CommandFailure("COMMAND_EXECUTION_STATE_FAILURE", "명령 실행 상태를 처리하지 못했습니다.");
    }
}
