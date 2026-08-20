package com.example.myagent.incident.adapter.out.ai;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.port.out.CandidateAnalysisPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class EmbabelCandidateAnalysisAdapter implements CandidateAnalysisPort {
    private final AgentPlatform agentPlatform;

    public EmbabelCandidateAnalysisAdapter(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @Override
    public Either<IncidentFailure, List<BugCandidate>> analyze(
        AnalysisEvidence evidence,
        SourceRevision sourceRevision,
        SourceContext sourceContext
    ) {
        return Try.of(() -> {
            var invocation = AgentInvocation.create(
                agentPlatform,
                IncidentAnalysisAgent.CandidateSet.class
            );
            var process = invocation.run(analysisInput(evidence, sourceRevision, sourceContext));
            return process.resultOfType(IncidentAnalysisAgent.CandidateSet.class)
                .candidates().stream()
                .map(draft -> toCandidate(draft, evidence.evidenceRefs()))
                .toList();
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "CANDIDATE_ANALYSIS_FAILED",
            "Embabel이 장애 후보를 생성하지 못했습니다."
        ));
    }

    private IncidentAnalysisAgent.AnalysisInput analysisInput(
        AnalysisEvidence evidence,
        SourceRevision sourceRevision,
        SourceContext sourceContext
    ) {
        if (evidence instanceof AnalysisEvidence.Jenkins jenkinsEvidence) {
            return new IncidentAnalysisAgent.JenkinsAnalysisInput(
                jenkinsEvidence,
                sourceRevision,
                sourceContext
            );
        }
        return new IncidentAnalysisAgent.ObservabilityAnalysisInput(
            (AnalysisEvidence.Observability) evidence,
            sourceRevision,
            sourceContext
        );
    }

    private BugCandidate toCandidate(
        IncidentAnalysisAgent.CandidateDraft draft,
        List<String> evidenceRefs
    ) {
        var draftEvidence = draft.evidence();
        var recommendation = draft.recommendation();
        return new BugCandidate(
            new BugCandidate.Identity(
                UUID.randomUUID().toString(),
                required(draft.title(), "제목 없음"),
                required(draft.rootCause(), "원인 근거 없음"),
                confidence(draft.confidence()),
                eligibility(draft.eligibility())
            ),
            new BugCandidate.Evidence(
                safeList(draftEvidence == null ? null : draftEvidence.sourceLocations()),
                safeList(evidenceRefs),
                safeList(draftEvidence == null ? null : draftEvidence.counterEvidence())
            ),
            new BugCandidate.Recommendation(
                required(recommendation == null ? null : recommendation.fixSummary(), "사람 검토 필요"),
                required(
                    recommendation == null ? null : recommendation.verificationSummary(),
                    "검증 계획 없음"
                )
            )
        );
    }

    private BugCandidate.Eligibility eligibility(String value) {
        return Try.of(() -> BugCandidate.Eligibility.valueOf(
            required(value, "INSUFFICIENT_EVIDENCE").toUpperCase(Locale.ROOT)
        )).getOrElse(BugCandidate.Eligibility.INSUFFICIENT_EVIDENCE);
    }

    private double confidence(Double value) {
        return value == null ? 0.0 : Math.max(0.0, Math.min(1.0, value));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().filter(this::hasText).toList();
    }

    private String required(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
