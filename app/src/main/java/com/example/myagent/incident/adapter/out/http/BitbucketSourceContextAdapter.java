package com.example.myagent.incident.adapter.out.http;

import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.global.support.SensitiveEvidenceRedactor;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.SourceContextPort;
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
    private static final Pattern SOURCE_PATH = Pattern.compile(
        "(?:[a-zA-Z0-9._$-]+/)+(?:src/(?:main|test)/(?:java|kotlin)/)"
            + "[a-zA-Z0-9._$/-]+\\.(?:java|kt)"
    );
    private static final Set<String> FORBIDDEN_PARTS = Set.of(
        "migration", "liquibase", "changelog", "secret", "jenkinsfile",
        "kubernetes", "/k8s/", "/helm/", "manifest", "fms-deploy"
    );

    private final BitbucketProperties properties;
    private final SensitiveEvidenceRedactor redactor;
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
            for (String path : sourcePaths(evidence)) {
                Optional<String> source = getSource(sourceRevision.commit(), path);
                if (source.isPresent() && acceptable(source.get(), files)) {
                    files.put(path, source.get());
                }
            }
            return new SourceContext(files);
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "SOURCE_CONTEXT_READ_FAILED",
            "고정된 Bitbucket commit에서 증거 관련 소스를 읽지 못했습니다."
        ));
    }

    private Set<String> sourcePaths(AnalysisEvidence evidence) {
        var paths = new LinkedHashSet<String>();
        var matcher = SOURCE_PATH.matcher(evidence.toString());
        while (matcher.find() && paths.size() < MAXIMUM_FILES) {
            String path = normalize(matcher.group());
            if (path.startsWith("eu/") && !isForbidden(path)) {
                paths.add(path);
            }
        }
        return paths;
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
}
