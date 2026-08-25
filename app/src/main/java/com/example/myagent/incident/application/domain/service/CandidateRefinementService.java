package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.RefineCandidateUseCase;
import com.example.myagent.incident.application.port.out.CandidateRefinementPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import com.example.myagent.incident.application.port.out.ObservabilityEvidencePort;
import com.example.myagent.incident.application.port.out.SourceContextPort;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CandidateRefinementService implements RefineCandidateUseCase {
    private static final int SCHEMA_VERSION = 1;

    private final IncidentStatePort statePort;
    private final JenkinsEvidencePort jenkinsEvidencePort;
    private final ObservabilityEvidencePort observabilityEvidencePort;
    private final SourceContextPort sourceContextPort;
    private final CandidateRefinementPort refinementPort;

    public CandidateRefinementService(
        IncidentStatePort statePort,
        JenkinsEvidencePort jenkinsEvidencePort,
        ObservabilityEvidencePort observabilityEvidencePort,
        SourceContextPort sourceContextPort,
        CandidateRefinementPort refinementPort
    ) {
        this.statePort = statePort;
        this.jenkinsEvidencePort = jenkinsEvidencePort;
        this.observabilityEvidencePort = observabilityEvidencePort;
        this.sourceContextPort = sourceContextPort;
        this.refinementPort = refinementPort;
    }

    @Override
    public synchronized AnalysisSession refine(RefinementCommand command) {
        var envelope = statePort.findAnalysis(command.analysisId())
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> exception("ANALYSIS_NOT_FOUND", "분석 작업을 찾지 못했습니다."));
        AnalysisSession session = envelope.session();
        if (session.identity().version() != command.analysisVersion()) {
            throw exception("ANALYSIS_VERSION_CONFLICT", "분석 결과가 갱신되었습니다. 새로고침 후 다시 요청하세요.");
        }
        AnalysisRequest request = envelope.request();
        if (request == null || session.snapshot().sourceRevision() == null) {
            throw exception("REFINEMENT_CONTEXT_MISSING", "정밀 분석에 필요한 원본 요청 정보가 없습니다.");
        }
        BugCandidate selected = session.result().candidates().stream()
            .filter(candidate -> candidate.identity().candidateId().equals(command.candidateId()))
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
            .map(candidate -> candidate.identity().candidateId().equals(command.candidateId())
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
        return statePort.saveAnalysis(new IncidentStatePort.AnalysisEnvelope(
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

    private IncidentUseCaseException failure(IncidentFailure value) {
        return exception(value.code(), value.message());
    }

    private IncidentUseCaseException exception(String code, String message) {
        return new IncidentUseCaseException(code, message);
    }
}
