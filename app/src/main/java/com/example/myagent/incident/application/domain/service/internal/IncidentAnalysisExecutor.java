package com.example.myagent.incident.application.domain.service.internal;

import com.example.myagent.global.annotation.InternalService;
import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.CandidateAnalysisPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import com.example.myagent.incident.application.port.out.ObservabilityEvidencePort;
import com.example.myagent.incident.application.port.out.SourceContextPort;
import com.example.myagent.incident.application.port.out.SourceRevisionPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@InternalService
public class IncidentAnalysisExecutor {
    private final SourceRevisionPort sourceRevisionPort;
    private final JenkinsEvidencePort jenkinsEvidencePort;
    private final ObservabilityEvidencePort observabilityEvidencePort;
    private final SourceContextPort sourceContextPort;
    private final CandidateAnalysisPort candidateAnalysisPort;
    private final AgentRuntimeProperties runtimeProperties;
    private final Clock clock;

    public IncidentAnalysisExecutor(
        SourceRevisionPort sourceRevisionPort,
        JenkinsEvidencePort jenkinsEvidencePort,
        ObservabilityEvidencePort observabilityEvidencePort,
        SourceContextPort sourceContextPort,
        CandidateAnalysisPort candidateAnalysisPort,
        AgentRuntimeProperties runtimeProperties,
        Clock clock
    ) {
        this.sourceRevisionPort = sourceRevisionPort;
        this.jenkinsEvidencePort = jenkinsEvidencePort;
        this.observabilityEvidencePort = observabilityEvidencePort;
        this.sourceContextPort = sourceContextPort;
        this.candidateAnalysisPort = candidateAnalysisPort;
        this.runtimeProperties = runtimeProperties;
        this.clock = clock;
    }

    public AnalysisSession requested(AnalysisRequest request, String requestHash) {
        Instant createdAt = clock.instant();
        return new AnalysisSession(
            new AnalysisSession.Identity(UUID.randomUUID().toString(), 1L, requestHash),
            new AnalysisSession.Snapshot(
                request.source(),
                null,
                createdAt,
                createdAt.plus(runtimeProperties.analysisTtl())
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.ANALYSIS_REQUESTED,
                List.of(),
                null
            )
        );
    }

    public AnalysisSession analyzing(AnalysisSession requested) {
        return withResult(requested, AnalysisSession.Status.ANALYZING, List.of(), null);
    }

    public void validateJenkinsEligibility(AnalysisRequest.Jenkins request) {
        SourceRevision sourceRevision = sourceRevisionPort.resolve(request.source())
            .getOrElseThrow(this::failure);
        var build = jenkinsEvidencePort.inspect(request).getOrElseThrow(this::failure);
        if (!sourceRevision.commit().equals(build.revision())) {
            throw new IncidentUseCaseException(
                "SOURCE_REVISION_MISMATCH",
                "Jenkins build revision과 요청 source revision이 다릅니다."
            );
        }
    }

    public AnalysisSession failed(AnalysisSession requested, Throwable throwable) {
        String failureReason = throwable instanceof IncidentUseCaseException useCaseException
            ? useCaseException.code() + ": " + useCaseException.getMessage()
            : "INCIDENT_ANALYSIS_FAILED: 장애 분석을 완료하지 못했습니다.";
        return withResult(
            requested,
            AnalysisSession.Status.FAILED,
            List.of(),
            failureReason
        );
    }

    public AnalysisSession execute(AnalysisRequest request, AnalysisSession requested) {
        SourceRevision sourceRevision = sourceRevisionPort.resolve(request.source())
            .getOrElseThrow(this::failure);
        AnalysisEvidence evidence = collectEvidence(request);
        validateRevision(evidence, sourceRevision);
        SourceContext sourceContext = sourceContextPort.read(evidence, sourceRevision)
            .getOrElseThrow(this::failure);
        var candidates = candidateAnalysisPort.analyze(evidence, sourceRevision, sourceContext)
            .getOrElseThrow(this::failure);
        return new AnalysisSession(
            requested.identity(),
            new AnalysisSession.Snapshot(
                request.source(),
                sourceRevision,
                requested.snapshot().createdAt(),
                requested.snapshot().expiresAt()
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                candidates,
                null
            )
        );
    }

    private AnalysisSession withResult(
        AnalysisSession session,
        AnalysisSession.Status status,
        List<BugCandidate> candidates,
        String failureReason
    ) {
        return new AnalysisSession(
            session.identity(),
            session.snapshot(),
            new AnalysisSession.Result(status, candidates, failureReason)
        );
    }

    private AnalysisEvidence collectEvidence(AnalysisRequest request) {
        if (request instanceof AnalysisRequest.Jenkins jenkinsRequest) {
            return jenkinsEvidencePort.collect(jenkinsRequest).getOrElseThrow(this::failure);
        }
        return observabilityEvidencePort.collect((AnalysisRequest.Observability) request)
            .getOrElseThrow(this::failure);
    }

    private void validateRevision(AnalysisEvidence evidence, SourceRevision sourceRevision) {
        if (evidence instanceof AnalysisEvidence.Jenkins jenkinsEvidence
            && !sourceRevision.commit().equals(jenkinsEvidence.revision())) {
            throw new IncidentUseCaseException(
                "SOURCE_REVISION_MISMATCH",
                "Jenkins build revision과 요청 source revision이 다릅니다."
            );
        }
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
