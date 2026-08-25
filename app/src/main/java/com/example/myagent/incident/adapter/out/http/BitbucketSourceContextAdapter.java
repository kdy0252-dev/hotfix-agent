package com.example.myagent.incident.adapter.out.http;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.policy.MigrationSafetyPolicy;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.SourceContextPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class BitbucketSourceContextAdapter implements SourceContextPort {
    private static final int MAXIMUM_FILES = 10;
    private static final int MAXIMUM_FILE_CHARACTERS = 60_000;
    private static final int MAXIMUM_TOTAL_CHARACTERS = 200_000;
    private static final int MAXIMUM_TREE_PAGES = 20;
    private static final int SOURCE_CONTEXT_RADIUS = 20;
    private static final Pattern SOURCE_PATH = Pattern.compile(
        "(?:[a-zA-Z0-9._$-]+/)+(?:src/(?:main|test)/(?:java|kotlin)/)"
            + "[a-zA-Z0-9._$/-]+\\.(?:java|kt)"
    );
    private static final Pattern MIGRATION_PATH = Pattern.compile(
        "(?i)(?=[a-zA-Z0-9._$/-]*(?:migration|liquibase|changelog))"
            + "(?:[a-zA-Z0-9._$-]+/)+[a-zA-Z0-9._$/-]+\\.(?:sql|xml|yaml|yml)"
    );
    private static final Pattern FILE_REFERENCE = Pattern.compile(
        "([A-Za-z_$][A-Za-z0-9_$-]*\\.(?:java|kt|sql|xml|yaml|yml))(?::(\\d+))?"
    );
    private static final Pattern QUALIFIED_CLASS = Pattern.compile(
        "(?:[a-z_][A-Za-z0-9_$]*\\.)+([A-Z][A-Za-z0-9_$]*)"
    );
    private static final Pattern METHOD_REFERENCE = Pattern.compile(
        "\\.([a-zA-Z_$][A-Za-z0-9_$]*)\\("
    );
    private static final Set<String> FORBIDDEN_PARTS = Set.of(
        "secret", "jenkinsfile", "kubernetes", "/k8s/", "/helm/", "manifest",
        "fms-deploy"
    );

    private final BitbucketProperties properties;
    private final SensitiveEvidenceRedactor redactor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public BitbucketSourceContextAdapter(
        BitbucketProperties properties,
        SensitiveEvidenceRedactor redactor
    ) {
        this.properties = properties;
        this.redactor = redactor;
    }

    @Override
    public Either<IncidentFailure, SourceContext> read(
        AnalysisEvidence evidence,
        SourceRevision sourceRevision
    ) {
        return Try.of(() -> {
            var files = new LinkedHashMap<String, String>();
            var hints = sourceHints(evidence);
            for (String path : sourcePaths(evidence, sourceRevision, hints)) {
                Optional<String> source = getSource(sourceRevision.commit(), path);
                if (source.isPresent()) {
                    String context = evidence instanceof AnalysisEvidence.Observability
                        ? relevantContext(path, source.get(), hints) : source.get();
                    if (acceptable(context, files)) {
                        files.put(path, context);
                    }
                }
            }
            return new SourceContext(files);
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "SOURCE_CONTEXT_READ_FAILED",
            "고정된 Bitbucket commit에서 증거 관련 소스를 읽지 못했습니다."
        ));
    }

    private Set<String> sourcePaths(
        AnalysisEvidence evidence,
        SourceRevision sourceRevision,
        SourceHints hints
    ) throws Exception {
        var paths = new LinkedHashSet<String>();
        addEvidencePaths(paths, SOURCE_PATH, evidence.toString());
        addEvidencePaths(paths, MIGRATION_PATH, evidence.toString());
        if (evidence instanceof AnalysisEvidence.Observability && paths.size() < MAXIMUM_FILES) {
            discoverSourcePaths(sourceRevision.commit(), hints.fileNames()).stream()
                .filter(path -> paths.size() < MAXIMUM_FILES)
                .forEach(paths::add);
        }
        return paths;
    }

    private void addEvidencePaths(Set<String> paths, Pattern pattern, String evidence) {
        var matcher = pattern.matcher(evidence);
        while (matcher.find() && paths.size() < MAXIMUM_FILES) {
            String path = normalize(matcher.group());
            if (path.startsWith("eu/") && !isForbidden(path)) {
                paths.add(path);
            }
        }
    }

    private Set<String> discoverSourcePaths(String commit, Set<String> fileNames)
        throws Exception {
        if (fileNames.isEmpty()) {
            return Set.of();
        }
        var paths = new LinkedHashSet<String>();
        URI next = repositoryUrl(
            "src/" + encode(commit) + "/eu?max_depth=20&pagelen=100"
        );
        int page = 0;
        while (next != null && page++ < MAXIMUM_TREE_PAGES && paths.size() < MAXIMUM_FILES) {
            JsonNode response = getJson(next);
            response.path("values").forEach(value -> {
                String path = value.path("path").asText();
                String fileName = Path.of(path).getFileName().toString();
                if (fileNames.contains(fileName) && isAllowedSourcePath(path)) {
                    paths.add(path);
                }
            });
            String nextUrl = response.path("next").asText();
            next = nextUrl.isBlank() ? null : URI.create(nextUrl);
        }
        return paths;
    }

    private JsonNode getJson(URI uri) throws Exception {
        var request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + properties.token())
            .header("Accept", "application/json")
            .GET()
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bitbucket returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private SourceHints sourceHints(AnalysisEvidence evidence) {
        String material = evidence.toString();
        var fileLines = new LinkedHashMap<String, Integer>();
        var fileNames = new LinkedHashSet<String>();
        var fileMatcher = FILE_REFERENCE.matcher(material);
        while (fileMatcher.find()) {
            fileNames.add(fileMatcher.group(1));
            if (fileMatcher.group(2) != null) {
                fileLines.put(fileMatcher.group(1), Integer.valueOf(fileMatcher.group(2)));
            }
        }
        var symbols = new LinkedHashSet<String>();
        var classMatcher = QUALIFIED_CLASS.matcher(material);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            fileNames.add(className + ".java");
            symbols.add(className);
        }
        var methodMatcher = METHOD_REFERENCE.matcher(material);
        while (methodMatcher.find()) {
            symbols.add(methodMatcher.group(1));
        }
        return new SourceHints(fileNames, fileLines, symbols);
    }

    private String relevantContext(String path, String content, SourceHints hints) {
        String[] lines = content.split("\\R", -1);
        var anchors = new TreeSet<Integer>();
        String fileName = Path.of(path).getFileName().toString();
        Optional.ofNullable(hints.fileLines().get(fileName)).ifPresent(anchors::add);
        for (int index = 0; index < lines.length && anchors.size() < 5; index++) {
            String line = lines[index];
            if (hints.symbols().stream().anyMatch(line::contains)) {
                anchors.add(index + 1);
            }
        }
        if (anchors.isEmpty()) {
            return content;
        }
        var includedLines = new TreeSet<Integer>();
        anchors.forEach(anchor -> {
            int start = Math.max(1, anchor - SOURCE_CONTEXT_RADIUS);
            int end = Math.min(lines.length, anchor + SOURCE_CONTEXT_RADIUS);
            for (int line = start; line <= end; line++) {
                includedLines.add(line);
            }
        });
        return includedLines.stream()
            .map(line -> "%d: %s".formatted(line, lines[line - 1]))
            .collect(Collectors.joining("\n"));
    }

    private boolean isAllowedSourcePath(String path) {
        String normalized = normalize(path);
        boolean applicationSource = (normalized.contains("/src/main/java/")
            || normalized.contains("/src/test/java/")
            || normalized.contains("/src/main/kotlin/")
            || normalized.contains("/src/test/kotlin/"))
            && (normalized.endsWith(".java") || normalized.endsWith(".kt"));
        return normalized.startsWith("eu/")
            && (applicationSource || MigrationSafetyPolicy.isMigrationPath(normalized))
            && !isForbidden(normalized);
    }

    private Optional<String> getSource(String commit, String path) throws Exception {
        var request = HttpRequest.newBuilder(repositoryUrl(
            "src/" + encode(commit) + '/' + encodePath(path)
        )).header("Authorization", "Bearer " + properties.token())
            .header("Accept", "text/plain")
            .GET()
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bitbucket returned HTTP " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    private boolean acceptable(String content, Map<String, String> files) {
        int currentCharacters = files.values().stream().mapToInt(String::length).sum();
        return content.length() <= MAXIMUM_FILE_CHARACTERS
            && currentCharacters + content.length() <= MAXIMUM_TOTAL_CHARACTERS
            && content.equals(redactor.redact(content));
    }

    private String normalize(String value) {
        String normalized = value.replace('\\', '/');
        int euRoot = normalized.indexOf("eu/");
        return euRoot < 0 ? normalized : normalized.substring(euRoot);
    }

    private boolean isForbidden(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return FORBIDDEN_PARTS.stream().anyMatch(normalized::contains)
            || normalized.endsWith(".sql")
            || normalized.endsWith(".key")
            || normalized.endsWith(".pem");
    }

    private URI repositoryUrl(String suffix) {
        return URI.create(properties.baseUrl().toString().replaceAll("/$", "")
            + "/repositories/" + encode(properties.workspace()) + '/'
            + encode(properties.repository()) + '/' + suffix);
    }

    private String encodePath(String value) {
        Path path = Path.of(value);
        return StreamSupport.stream(path.spliterator(), false)
            .map(Path::toString)
            .map(this::encode)
            .collect(Collectors.joining("/"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record SourceHints(
        Set<String> fileNames,
        Map<String, Integer> fileLines,
        Set<String> symbols
    ) {
        private SourceHints {
            fileNames = Set.copyOf(fileNames);
            fileLines = Map.copyOf(fileLines);
            symbols = Set.copyOf(symbols);
        }
    }
}
