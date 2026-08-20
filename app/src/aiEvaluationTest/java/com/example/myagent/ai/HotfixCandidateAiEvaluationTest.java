package com.example.myagent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HotfixCandidateAiEvaluationTest {

    private static final double PASS_THRESHOLD = 0.70;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Test
    void evaluatesHotfixCandidateAndPublishesTheScoreToLangfuse() throws Exception {
        var environment = EvaluationEnvironment.load();
        var sessionId = "ai-eval-" + UUID.randomUUID();
        var evaluation = evaluate(environment.liteLlm(), hotfixCandidate());

        var scoreId = publishScore(environment.langfuse(), sessionId, evaluation);

        assertThat(evaluation.score()).isBetween(0.0, 1.0);
        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.score()).isGreaterThanOrEqualTo(PASS_THRESHOLD);
        assertThat(scoreId).isNotBlank();
    }

    private JudgeResult evaluate(LiteLlmSettings settings, String candidate) throws Exception {
        var prompt = """
            You are a strict SRE reviewer. Evaluate the hotfix candidate below.
            Score it from 0.0 to 1.0 using these equally weighted criteria:
            evidence-based diagnosis, safe change scope, test plan, and draft-PR policy compliance.
            passed must be true only when score is at least %.2f.
            Return JSON only: {"score":0.0,"passed":false,"reason":"short reason"}.

            Candidate:
            %s
            """.formatted(PASS_THRESHOLD, candidate);
        var body = OBJECT_MAPPER.createObjectNode();
        body.put("model", settings.model());
        body.put("max_tokens", 300);
        body.putArray("messages")
            .addObject()
            .put("role", "user")
            .put("content", prompt);

        var response = sendJson(
            settings.baseUrl().resolve("v1/chat/completions"),
            "Bearer " + settings.apiKey(),
            body.toString()
        );
        assertThat(response.statusCode())
            .withFailMessage("LiteLLM returned %s: %s", response.statusCode(), response.body())
            .isBetween(200, 299);

        var content = OBJECT_MAPPER.readTree(response.body())
            .path("choices").path(0).path("message").path("content").asText();
        return OBJECT_MAPPER.readValue(extractJson(content), JudgeResult.class);
    }

    private String publishScore(
        LangfuseSettings settings,
        String sessionId,
        JudgeResult evaluation
    ) throws Exception {
        var body = OBJECT_MAPPER.createObjectNode();
        body.put("id", "hotfix-candidate-quality-" + sessionId);
        body.put("sessionId", sessionId);
        body.put("name", "hotfix-candidate-quality");
        body.put("value", evaluation.score());
        body.put("dataType", "NUMERIC");
        body.put("comment", evaluation.reason());

        var credentials = settings.publicKey() + ":" + settings.secretKey();
        var basicAuth = Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8)
        );
        var response = sendLangfuseJson(
            settings.baseUrl().resolve("api/public/scores"),
            "Basic " + basicAuth,
            body.toString()
        );
        assertThat(response.statusCode())
            .withFailMessage("Langfuse returned %s: %s", response.statusCode(), response.body())
            .isBetween(200, 299);
        return OBJECT_MAPPER.readTree(response.body()).path("id").asText();
    }

    private JsonResponse sendLangfuseJson(URI uri, String authorization, String body)
        throws IOException {
        var bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            connection.setReadTimeout((int) Duration.ofMinutes(3).toMillis());
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            connection.setDoOutput(true);
            try (var outputStream = connection.getOutputStream()) {
                outputStream.write(bodyBytes);
            }
            var statusCode = connection.getResponseCode();
            var responseStream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
            var responseBody = responseStream == null
                ? ""
                : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonResponse(statusCode, responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private HttpResponse<String> sendJson(URI uri, String authorization, String body)
        throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(3))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String extractJson(String response) {
        var start = response.indexOf('{');
        var end = response.lastIndexOf('}');
        assertThat(start)
            .withFailMessage("Judge response did not contain JSON: %s", response)
            .isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return response.substring(start, end + 1);
    }

    private String hotfixCandidate() {
        return """
            Evidence: Jenkins build 181 fails with a NullPointerException at BookingService.java:84.
            Diagnosis: the nullable repository result is dereferenced before the existing not-found branch.
            Scope: add the missing empty-result guard in BookingService and one regression test; two files,
            fewer than 40 changed lines. No migration, secret, Jenkinsfile, or deployment manifest changes.
            Verification: run the focused regression test, ./gradlew test, architectureTest, and check.
            Delivery: create agent/hotfix/booking-null-guard from the selected source branch and open a
            Draft Bitbucket PR only after all local Jenkins-equivalent checks pass. Never merge, tag, or deploy.
            """;
    }

    private record JudgeResult(double score, boolean passed, String reason) {
    }

    private record LiteLlmSettings(URI baseUrl, String apiKey, String model) {
    }

    private record LangfuseSettings(URI baseUrl, String publicKey, String secretKey) {
    }

    private record JsonResponse(int statusCode, String body) {
    }

    private record EvaluationEnvironment(
        LiteLlmSettings liteLlm,
        LangfuseSettings langfuse
    ) {

        static EvaluationEnvironment load() throws Exception {
            var projectRoot = Path.of(requiredSystemProperty("ai.test.project-root"));
            var localEnvironment = loadZshEnvironment(projectRoot.resolve(".env.local"));
            var langfuseEnvironment = loadEnvironmentFile(
                Path.of(requiredSystemProperty("ai.test.langfuse-env"))
            );
            return new EvaluationEnvironment(
                new LiteLlmSettings(
                    withTrailingSlash(required(localEnvironment, "LITELLM_BASE_URL")),
                    required(localEnvironment, "LITELLM_API_KEY"),
                    required(localEnvironment, "LITELLM_MODEL")
                ),
                new LangfuseSettings(
                    URI.create("http://127.0.0.1:13000/"),
                    required(langfuseEnvironment, "LANGFUSE_PUBLIC_KEY"),
                    required(langfuseEnvironment, "LANGFUSE_SECRET_KEY")
                )
            );
        }

        private static Map<String, String> loadZshEnvironment(Path environmentFile) throws Exception {
            assertThat(environmentFile)
                .withFailMessage("Missing %s. Run ./scripts/setup-env-local.zsh first.", environmentFile)
                .exists();
            var command = "source \"$1\"; "
                + "print -r -- \"LITELLM_BASE_URL=${LITELLM_BASE_URL}\"; "
                + "print -r -- \"LITELLM_API_KEY=${LITELLM_API_KEY}\"; "
                + "print -r -- \"LITELLM_MODEL=${LITELLM_MODEL}\"";
            var process = new ProcessBuilder("zsh", "-c", command, "ai-test", environmentFile.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).isZero();
            return parseEnvironmentLines(output.lines());
        }

        private static Map<String, String> loadEnvironmentFile(Path environmentFile) throws IOException {
            return parseEnvironmentLines(
                Files.readAllLines(environmentFile).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
            );
        }

        private static Map<String, String> parseEnvironmentLines(Stream<String> lines) {
            return lines.filter(line -> line.contains("="))
                .map(line -> line.split("=", 2))
                .collect(Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1]));
        }

        private static URI withTrailingSlash(String value) {
            return URI.create(value.endsWith("/") ? value : value + "/");
        }

        private static String required(Map<String, String> environment, String key) {
            var value = environment.get(key);
            assertThat(value).withFailMessage("Missing required setting: %s", key).isNotBlank();
            return value;
        }

        private static String requiredSystemProperty(String key) {
            var value = System.getProperty(key);
            assertThat(value).withFailMessage("Missing required system property: %s", key).isNotBlank();
            return value;
        }
    }
}
