package com.example.myagent.incident.application.domain.service.internal;

import com.example.myagent.global.annotation.InternalService;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.CandidateRefinementPort;
import com.example.myagent.incident.application.port.out.CandidateRefinementTaskPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import com.example.myagent.incident.application.port.out.ObservabilityEvidencePort;
import com.example.myagent.incident.application.port.out.SourceContextPort;
import io.vavr.control.Try;
import java.time.Clock;
import java.util.List;

@InternalService
public class CandidateRefinementExecutor {
    private static final int SCHEMA_VERSION = 1;

    private final IncidentStatePort statePort;
    private final CandidateRefinementTaskPort taskPort;
    private final JenkinsEvidencePort jenkinsEvidencePort;
    private final ObservabilityEvidencePort observabilityEvidencePort;
    private final SourceContextPort sourceContextPort;
    private final CandidateRefinementPort refinementPort;
    private final Clock clock;

    public CandidateRefinementExecutor(
        IncidentStatePort statePort,
        CandidateRefinementTaskPort taskPort,
        JenkinsEvidencePort jenkinsEvidencePort,
        ObservabilityEvidencePort observabilityEvidencePort,
        SourceContextPort sourceContextPort,
        CandidateRefinementPort refinementPort,
        Clock clock
    ) {
        this.statePort = statePort;
        this.taskPort = taskPort;
        this.jenkinsEvidencePort = jenkinsEvidencePort;
        this.observabilityEvidencePort = observabilityEvidencePort;
        this.sourceContextPort = sourceContextPort;
        this.refinementPort = refinementPort;
        this.clock = clock;
    }

    public void execute(CandidateRefinementTask task) {
        saveTask(task, CandidateRefinementTask.Status.RUNNING, null);
        Try.run(() -> refine(task))
            .onSuccess(ignored -> saveTask(task, CandidateRefinementTask.Status.COMPLETED, null))
            .onFailure(exception -> saveTask(
                task,
                CandidateRefinementTask.Status.FAILED,
                failureMessage(exception)
            ));
    }

    private void refine(CandidateRefinementTask task) {
        var envelope = statePort.findAnalysis(task.analysisId())
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> exception("ANALYSIS_NOT_FOUND", "분석 작업을 찾지 못했습니다."));
        AnalysisSession session = envelope.session();
        AnalysisRequest request = envelope.request();
        if (request == null || session.snapshot().sourceRevision() == null) {
            throw exception("REFINEMENT_CONTEXT_MISSING", "정밀 분석에 필요한 원본 요청 정보가 없습니다.");
        }
        BugCandidate selected = session.result().candidates().stream()
            .filter(candidate -> candidate.identity().candidateId().equals(task.candidateId()))
            .findFirst()
            .orElseThrow(() -> exception("CANDIDATE_NOT_FOUND", "정밀 분석할 후보를 찾지 못했습니다."));
        AnalysisEvidence evidence = collect(request);
        var context = sourceContextPort.read(evidence, session.snapshot().sourceRevision())
            .getOrElseThrow(this::failure);
        BugCandidate refined = refinementPort.refine(
            selected,
            evidence,
            session.snapshot().sourceRevision(),
            context
        ).getOrElseThrow(this::failure);
        List<BugCandidate> candidates = session.result().candidates().stream()
            .map(candidate -> candidate.identity().candidateId().equals(task.candidateId())
                ? refined : candidate)
            .toList();
        var updated = new AnalysisSession(
            new AnalysisSession.Identity(
                session.identity().analysisId(),
                session.identity().version() + 1,
                session.identity().requestHash()
            ),
            session.snapshot(),
            new AnalysisSession.Result(AnalysisSession.Status.CANDIDATES_READY, candidates, null)
        );
        statePort.saveAnalysis(new IncidentStatePort.AnalysisEnvelope(
            SCHEMA_VERSION,
            envelope.idempotencyKey(),
            envelope.requestHash(),
            updated,
            request
        )).getOrElseThrow(this::failure);
    }

    private AnalysisEvidence collect(AnalysisRequest request) {
        return request instanceof AnalysisRequest.Jenkins jenkins
            ? jenkinsEvidencePort.collect(jenkins).getOrElseThrow(this::failure)
            : observabilityEvidencePort.collect((AnalysisRequest.Observability) request)
                .getOrElseThrow(this::failure);
    }

    private void saveTask(
        CandidateRefinementTask task,
        CandidateRefinementTask.Status status,
        String failureReason
    ) {
        taskPort.save(new CandidateRefinementTask(
            task.taskId(),
            task.analysisId(),
            task.candidateId(),
            status,
            failureReason,
            task.requestedAt(),
            clock.instant()
        )).getOrElseThrow(this::failure);
    }

    private String failureMessage(Throwable throwable) {
        return throwable instanceof IncidentUseCaseException useCaseException
            ? useCaseException.getMessage()
            : "정밀 AI 분석을 완료하지 못했습니다. 다시 요청해 주세요.";
    }

    private IncidentUseCaseException failure(IncidentFailure value) {
        return exception(value.code(), value.message());
    }

    private IncidentUseCaseException exception(String code, String message) {
        return new IncidentUseCaseException(code, message);
    }
}
