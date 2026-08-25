package com.example.myagent.incident.adapter.out.ai;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisEvidence;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceContext;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.port.out.CandidateRefinementPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.util.List;
import java.util.Locale;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class EmbabelCandidateRefinementAdapter implements CandidateRefinementPort {
    private final AgentPlatform agentPlatform;

    public EmbabelCandidateRefinementAdapter(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @Override
    public Either<IncidentFailure, BugCandidate> refine(
        BugCandidate candidate,
        AnalysisEvidence evidence,
        SourceRevision revision,
        SourceContext sourceContext
    ) {
        return Try.of(() -> {
            var invocation = AgentInvocation.create(
                agentPlatform,
                CandidateRefinementAgent.RefinedCandidate.class
            );
            var result = invocation.run(new CandidateRefinementAgent.RefinementInput(
                candidate,
                evidence,
                revision,
                sourceContext
            )).resultOfType(CandidateRefinementAgent.RefinedCandidate.class);
            return toCandidate(candidate, result);
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "CANDIDATE_REFINEMENT_FAILED",
            "정밀 AI 분석으로 원인 후보를 검증하지 못했습니다."
        ));
    }

    private BugCandidate toCandidate(
        BugCandidate original,
        CandidateRefinementAgent.RefinedCandidate result
    ) {
        return new BugCandidate(
            new BugCandidate.Identity(
                original.identity().candidateId(),
                text(result.title(), original.identity().title()),
                text(result.rootCause(), original.identity().rootCause()),
                result.confidence() == null ? original.identity().confidence()
                    : Math.max(0.0, Math.min(1.0, result.confidence())),
                eligibility(result.eligibility())
            ),
            new BugCandidate.Evidence(
                values(result.sourceLocations()),
                original.evidence().evidenceRefs(),
                values(result.counterEvidence())
            ),
            new BugCandidate.Recommendation(
                text(result.fixSummary(), original.recommendation().fixSummary()),
                text(
                    result.verificationSummary(),
                    original.recommendation().verificationSummary()
                )
            )
        );
    }

    private BugCandidate.Eligibility eligibility(String value) {
        return Try.of(() -> BugCandidate.Eligibility.valueOf(
            text(value, "INSUFFICIENT_EVIDENCE").toUpperCase(Locale.ROOT)
        )).getOrElse(BugCandidate.Eligibility.INSUFFICIENT_EVIDENCE);
    }

    private List<String> values(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .toList();
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
