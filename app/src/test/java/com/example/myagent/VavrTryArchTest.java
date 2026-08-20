package com.example.myagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.vavr.control.Try;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VavrTryArchTest {
    private static final Pattern JAVA_TRY = Pattern.compile("\\btry\\s*[({]");

    @Test
    void detectorCatchesMultilineTryAndTryWithResources(@TempDir Path temporaryDirectory) {
        Path fixture = temporaryDirectory.resolve("Violation.java");
        Try.run(() -> Files.writeString(fixture, """
            class Violation {
                void block() {
                    try
                    {
                    }
                }
                void resource() {
                    try
                    (var ignored = resource()) {
                    }
                }
            }
            """)).getOrElseThrow(exception -> new IllegalStateException("Cannot write fixture", exception));

        assertThat(violations(fixture)).hasSize(2);
    }

    @Test
    @SuppressWarnings("StreamResourceLeak")
    void productionCodeMustUseVavrTryInsteadOfJavaTryStatements() {
        List<String> violations = Try.withResources(() -> Files.walk(sourceRoot()))
            .of(paths -> paths.filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> violations(path).stream())
                .toList())
            .getOrElseThrow(exception -> new IllegalStateException(
                "Cannot inspect production Java sources",
                exception
            ));

        assertThat(violations)
            .as("Use io.vavr.control.Try instead of Java try/catch or try-with-resources: %s",
                violations)
            .isEmpty();
    }

    private static List<String> violations(Path path) {
        return Try.of(() -> {
            String source = Files.readString(path);
            return JAVA_TRY.matcher(source).results()
                .map(result -> sourceRoot().relativize(path) + ":" + lineNumber(
                    source,
                    result.start()
                ))
                .toList();
        }).getOrElseThrow(exception -> new IllegalStateException("Cannot read " + path, exception));
    }

    private static long lineNumber(String source, int offset) {
        return source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }

    private static Path sourceRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path moduleSourceRoot = current.resolve("app/src/main/java");
            if (Files.isDirectory(moduleSourceRoot)) {
                return moduleSourceRoot;
            }
            Path projectSourceRoot = current.resolve("src/main/java");
            if (Files.isDirectory(projectSourceRoot)) {
                return projectSourceRoot;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot resolve main Java source root");
    }
}
