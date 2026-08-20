package com.example.myagent.incident.adapter.out.persistence;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Adapter
@Component
public class JsonIncidentStatePersistenceAdapter implements IncidentStatePort {
    private final ObjectMapper objectMapper;
    private final Path analysisDirectory;
    private final Path hotfixDirectory;

    public JsonIncidentStatePersistenceAdapter(
        ObjectMapper objectMapper,
        @Value("${agent.runtime.state-path:.agent/runtime}") String statePath
    ) {
        this.objectMapper = objectMapper;
        Path root = Path.of(statePath).toAbsolutePath().normalize();
        this.analysisDirectory = root.resolve("analyses");
        this.hotfixDirectory = root.resolve("hotfixes");
    }

    @Override
    public synchronized Either<IncidentFailure, AnalysisSession> saveAnalysis(
        AnalysisEnvelope envelope
    ) {
        return Try.of(() -> {
            write(
                analysisDirectory,
                envelope.session().identity().analysisId(),
                envelope
            );
            return envelope.session();
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysis(String analysisId) {
        return readById(analysisDirectory, analysisId, AnalysisEnvelope.class);
    }

    @Override
    public Either<IncidentFailure, Optional<AnalysisEnvelope>> findAnalysisByIdempotencyKey(
        String key
    ) {
        return findByKey(analysisDirectory, AnalysisEnvelope.class, key);
    }

    @Override
    public synchronized Either<IncidentFailure, HotfixResource> saveHotfix(HotfixEnvelope envelope) {
        return Try.of(() -> {
            write(hotfixDirectory, envelope.resource().identity().hotfixId(), envelope);
            return envelope.resource();
        }).toEither().mapLeft(this::failure);
    }

    @Override
    public Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfix(String hotfixId) {
        return readById(hotfixDirectory, hotfixId, HotfixEnvelope.class);
    }

    @Override
    public Either<IncidentFailure, Optional<HotfixEnvelope>> findHotfixByIdempotencyKey(String key) {
        return findByKey(hotfixDirectory, HotfixEnvelope.class, key);
    }

    private <T> Either<IncidentFailure, Optional<T>> readById(
        Path directory,
        String id,
        Class<T> type
    ) {
        return Try.of(() -> {
            Path path = stateFile(directory, id);
            return Files.exists(path)
                ? Optional.of(objectMapper.readValue(path.toFile(), type)) : Optional.<T>empty();
        }).toEither().mapLeft(this::failure);
    }

    @SuppressWarnings("StreamResourceLeak")
    private <T> Either<IncidentFailure, Optional<T>> findByKey(
        Path directory,
        Class<T> type,
        String key
    ) {
        return Try.of(() -> {
            if (!Files.isDirectory(directory)) {
                return Optional.<T>empty();
            }
            return Try.withResources(() -> Files.list(directory))
                .of(paths -> paths.filter(this::isJsonFile)
                    .map(path -> readUnchecked(path, type))
                    .filter(value -> key.equals(idempotencyKey(value)))
                    .findFirst())
                .get();
        }).toEither().mapLeft(this::failure);
    }

    private String idempotencyKey(Object envelope) {
        if (envelope instanceof AnalysisEnvelope analysisEnvelope) {
            return analysisEnvelope.idempotencyKey();
        }
        return ((HotfixEnvelope) envelope).idempotencyKey();
    }

    private <T> T readUnchecked(Path path, Class<T> type) {
        return Try.of(() -> objectMapper.readValue(path.toFile(), type))
            .getOrElseThrow(exception -> new IllegalStateException(
                "Invalid incident state file",
                exception
            ));
    }

    private void write(Path directory, String id, Object value) throws IOException {
        Files.createDirectories(directory);
        Path target = stateFile(directory, id);
        Path temporaryFile = Files.createTempFile(directory, "state-", ".tmp");
        objectMapper.writeValue(temporaryFile.toFile(), value);
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

    private Path stateFile(Path directory, String id) {
        return directory.resolve(UUID.fromString(id).toString() + ".json");
    }

    private boolean isJsonFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json");
    }

    private IncidentFailure failure(Throwable throwable) {
        return new IncidentFailure("INCIDENT_STATE_FAILURE", "장애 분석 상태를 처리하지 못했습니다.");
    }
}
