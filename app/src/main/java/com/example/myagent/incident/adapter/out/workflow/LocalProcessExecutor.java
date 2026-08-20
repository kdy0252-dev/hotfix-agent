package com.example.myagent.incident.adapter.out.workflow;

import io.vavr.control.Try;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class LocalProcessExecutor {
    private static final int MAX_OUTPUT_BYTES = 1_000_000;

    private LocalProcessExecutor() {
    }

    static Result run(Path directory, List<String> command) throws IOException,
        InterruptedException {
        return run(directory, command, Map.of(), Duration.ofMinutes(30));
    }

    static Result run(
        Path directory,
        List<String> command,
        Map<String, String> environment,
        Duration timeout
    ) throws IOException, InterruptedException {
        Path output = Files.createTempFile("agent-command-", ".log");
        return Try.of(() -> {
            var processBuilder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
            processBuilder.environment().putAll(environment);
            Process process = processBuilder.start();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                return new Result(124, "Command timed out: " + command.getFirst());
            }
            byte[] bytes = Files.readAllBytes(output);
            int length = Math.min(bytes.length, MAX_OUTPUT_BYTES);
            return new Result(
                process.exitValue(),
                new String(bytes, 0, length, StandardCharsets.UTF_8)
            );
        }).andFinally(() -> delete(output)).get();
    }

    private static void delete(Path path) {
        Try.run(() -> Files.deleteIfExists(path)).get();
    }

    record Result(int exitCode, String output) {
        boolean successful() {
            return exitCode == 0;
        }
    }
}
