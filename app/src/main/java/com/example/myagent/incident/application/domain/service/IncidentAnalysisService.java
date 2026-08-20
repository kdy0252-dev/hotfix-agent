package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.service.internal.IncidentAnalysisExecutor;
import com.example.myagent.incident.application.domain.service.support.IncidentRequestHash;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.IncidentStatePort.AnalysisEnvelope;
import io.vavr.control.Try;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class IncidentAnalysisService implements AnalyzeIncidentUseCase {
    private static final int SCHEMA_VERSION = 1;

    private final IncidentAnalysisExecutor executor;
    private final IncidentStatePort statePort;
    private final TaskExecutor taskExecutor;
    private final Set<String> activeAnalyses = ConcurrentHashMap.newKeySet();

    public IncidentAnalysisService(
        IncidentAnalysisExecutor executor,
        IncidentStatePort statePort,
        TaskExecutor taskExecutor
    ) {
        this.executor = executor;
        this.statePort = statePort;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public AnalysisSession analyzeJenkins(AnalysisCommand<AnalysisRequest.Jenkins> command) {
        return analyze(command.request(), command.idempotencyKey());
    }

    @Override
    public AnalysisSession analyzeObservability(
        AnalysisCommand<AnalysisRequest.Observability> command
    ) {
        return analyze(command.request(), command.idempotencyKey());
    }

    private synchronized AnalysisSession analyze(
        AnalysisRequest request,
        String idempotencyKey
    ) {
        requireIdempotencyKey(idempotencyKey);
        validateRequest(request);
        String requestHash = IncidentRequestHash.calculate(request);
        var previous = statePort.findAnalysisByIdempotencyKey(idempotencyKey)
            .getOrElseThrow(this::failure);
        if (previous.isPresent()) {
            AnalysisSession replayed = replay(previous.get(), requestHash);
            resumeIfIncomplete(request, previous.get(), replayed);
            return replayed;
        }
        if (request instanceof AnalysisRequest.Jenkins jenkinsRequest) {
            executor.validateJenkinsEligibility(jenkinsRequest);
        }
        AnalysisSession requested = executor.requested(request, requestHash);
        AnalysisEnvelope envelope = new AnalysisEnvelope(
            SCHEMA_VERSION,
            idempotencyKey,
            requestHash,
            requested
        );
        AnalysisSession saved = statePort.saveAnalysis(envelope).getOrElseThrow(this::failure);
        submit(request, envelope);
        return saved;
    }

    private void resumeIfIncomplete(
        AnalysisRequest request,
        AnalysisEnvelope envelope,
        AnalysisSession session
    ) {
        if (session.result().status() == AnalysisSession.Status.ANALYSIS_REQUESTED
            || session.result().status() == AnalysisSession.Status.ANALYZING) {
            submit(request, envelope);
        }
    }

    private void submit(AnalysisRequest request, AnalysisEnvelope envelope) {
        String analysisId = envelope.session().identity().analysisId();
        if (!activeAnalyses.add(analysisId)) {
            return;
        }
        Try.run(() -> taskExecutor.execute(() -> Try.run(() -> process(request, envelope))
                .andFinally(() -> activeAnalyses.remove(analysisId))
                .get()))
            .onFailure(exception -> activeAnalyses.remove(analysisId));
    }

    private void process(AnalysisRequest request, AnalysisEnvelope envelope) {
        AnalysisSession analyzing = executor.analyzing(envelope.session());
        save(envelope, analyzing);
        AnalysisSession completed = Try.of(() -> executor.execute(request, envelope.session()))
            .getOrElseGet(exception -> executor.failed(envelope.session(), exception));
        save(envelope, completed);
    }

    private void save(AnalysisEnvelope envelope, AnalysisSession session) {
        statePort.saveAnalysis(new AnalysisEnvelope(
            envelope.schemaVersion(),
            envelope.idempotencyKey(),
            envelope.requestHash(),
            session
        )).getOrElseThrow(this::failure);
    }

    private AnalysisSession replay(AnalysisEnvelope envelope, String requestHash) {
        if (!envelope.requestHash().equals(requestHash)) {
            throw new IncidentUseCaseException(
                "IDEMPOTENCY_KEY_REUSED",
                "같은 idempotency key에 다른 분석 요청을 사용할 수 없습니다."
            );
        }
        return envelope.session();
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IncidentUseCaseException(
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key 헤더가 필요합니다."
            );
        }
    }

    private void validateRequest(AnalysisRequest request) {
        if (request == null || request.source() == null) {
            throw invalidRequest("source가 필요합니다.");
        }
        var source = request.source();
        if (source.type() == null
            || (source.type() == SourceSpec.Type.BRANCH
                && (source.branchName() == null || source.branchName().isBlank()))
            || (source.type() == SourceSpec.Type.PULL_REQUEST
                && (source.pullRequestId() == null || source.pullRequestId() <= 0))) {
            throw invalidRequest("유효한 branch 또는 pull request source가 필요합니다.");
        }
        if (request instanceof AnalysisRequest.Jenkins jenkins && jenkins.buildNumber() <= 0) {
            throw invalidRequest("buildNumber는 양수여야 합니다.");
        }
        if (request instanceof AnalysisRequest.Observability observability) {
            validateObservationRange(observability);
        }
    }

    private void validateObservationRange(AnalysisRequest.Observability request) {
        var range = request.timeRange();
        if (request.environment() == null || range == null
            || range.startAt() == null || range.endAt() == null) {
            throw invalidRequest("환경과 관측 시작/종료 시각이 필요합니다.");
        }
        Duration duration = Duration.between(range.startAt(), range.endAt());
        if (duration.isNegative() || duration.isZero()
            || duration.compareTo(Duration.ofHours(1)) > 0) {
            throw invalidRequest("관측 범위는 1초 이상 60분 이하여야 합니다.");
        }
    }

    private IncidentUseCaseException invalidRequest(String message) {
        return new IncidentUseCaseException("INVALID_ANALYSIS_REQUEST", message);
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
