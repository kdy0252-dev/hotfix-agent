package com.example.myagent.incident.application.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.CandidateAnalysisPort;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import com.example.myagent.incident.application.port.out.ObservabilityEvidencePort;
import com.example.myagent.incident.application.port.out.SourceContextPort;
import com.example.myagent.incident.application.port.out.SourceRevisionPort;
import io.vavr.control.Either;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IncidentAnalysisExecutorTest {
    private SourceRevisionPort sourceRevisionPort;
    private JenkinsEvidencePort jenkinsEvidencePort;
    private IncidentAnalysisExecutor executor;

    @BeforeEach
    void setUp() {
        sourceRevisionPort = mock(SourceRevisionPort.class);
        jenkinsEvidencePort = mock(JenkinsEvidencePort.class);
        executor = new IncidentAnalysisExecutor(
            sourceRevisionPort,
            jenkinsEvidencePort,
            mock(ObservabilityEvidencePort.class),
            mock(SourceContextPort.class),
            mock(CandidateAnalysisPort.class),
            new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.REPORT_ONLY,
                Path.of("/tmp/fms"),
                Duration.ofHours(24)
            ),
            Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsJenkinsAndSourceRevisionMismatchDuringMetadataPreflight() {
        var request = new AnalysisRequest.Jenkins(
            "FMS-EU/job/main",
            181,
            SourceSpec.branch("main")
        );
        when(sourceRevisionPort.resolve(request.source())).thenReturn(Either.right(
            new SourceRevision("source-commit", "main", "bitbucket:branch:main")
        ));
        when(jenkinsEvidencePort.inspect(request)).thenReturn(Either.right(
            new JenkinsEvidencePort.BuildSnapshot("build-commit")
        ));

        assertThatThrownBy(() -> executor.validateJenkinsEligibility(request))
            .isInstanceOf(IncidentUseCaseException.class)
            .extracting(exception -> ((IncidentUseCaseException) exception).code())
            .isEqualTo("SOURCE_REVISION_MISMATCH");
    }

    @Test
    void storesASpecificMaskedFailureCodeInTheTerminalAnalysisState() {
        var request = new AnalysisRequest.Observability(
            new AnalysisRequest.TimeRange(
                OffsetDateTime.parse("2026-08-20T00:00:00Z"),
                OffsetDateTime.parse("2026-08-20T00:20:00Z")
            ),
            AnalysisRequest.Environment.PROD,
            SourceSpec.branch("main")
        );
        var requested = executor.requested(request, "request-hash");

        var failed = executor.failed(requested, new IncidentUseCaseException(
            "GRAFANA_READ_FAILED",
            "Grafana 관측 증거를 수집하지 못했습니다."
        ));

        assertThat(failed.result().failureReason())
            .isEqualTo("GRAFANA_READ_FAILED: Grafana 관측 증거를 수집하지 못했습니다.");
    }
}
