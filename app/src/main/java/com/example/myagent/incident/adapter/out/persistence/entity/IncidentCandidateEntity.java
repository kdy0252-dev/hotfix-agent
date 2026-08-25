package com.example.myagent.incident.adapter.out.persistence.entity;

import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "incident_candidates", schema = "hotfix_agent")
public class IncidentCandidateEntity {
    @Id
    @Column(name = "candidate_id", length = 36)
    private String candidateId;
    @ManyToOne(optional = false)
    @JoinColumn(name = "analysis_id", nullable = false)
    @SuppressWarnings("UnusedVariable")
    private IncidentAnalysisEntity analysis;
    @Column(name = "item_order", nullable = false)
    private int itemOrder;
    @Column(nullable = false)
    private String title;
    @Column(name = "root_cause", nullable = false)
    private String rootCause;
    private double confidence;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BugCandidate.Eligibility eligibility;
    @Column(name = "fix_summary", nullable = false)
    private String fixSummary;
    @Column(name = "verification_summary", nullable = false)
    private String verificationSummary;

    @ElementCollection
    @CollectionTable(
        name = "incident_candidate_source_locations",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "candidate_id")
    )
    @OrderColumn(name = "item_order")
    @Column(name = "value", nullable = false)
    private List<String> sourceLocations = new ArrayList<>();

    @ElementCollection
    @CollectionTable(
        name = "incident_candidate_evidence_refs",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "candidate_id")
    )
    @OrderColumn(name = "item_order")
    @Column(name = "value", nullable = false)
    private List<String> evidenceRefs = new ArrayList<>();

    @ElementCollection
    @CollectionTable(
        name = "incident_candidate_counter_evidence",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "candidate_id")
    )
    @OrderColumn(name = "item_order")
    @Column(name = "value", nullable = false)
    private List<String> counterEvidence = new ArrayList<>();

    protected IncidentCandidateEntity() {
    }

    static IncidentCandidateEntity from(
        BugCandidate candidate,
        IncidentAnalysisEntity analysis,
        int itemOrder
    ) {
        var entity = new IncidentCandidateEntity();
        entity.candidateId = candidate.identity().candidateId();
        entity.analysis = analysis;
        entity.itemOrder = itemOrder;
        entity.title = candidate.identity().title();
        entity.rootCause = candidate.identity().rootCause();
        entity.confidence = candidate.identity().confidence();
        entity.eligibility = candidate.identity().eligibility();
        entity.fixSummary = candidate.recommendation().fixSummary();
        entity.verificationSummary = candidate.recommendation().verificationSummary();
        entity.sourceLocations.addAll(candidate.evidence().sourceLocations());
        entity.evidenceRefs.addAll(candidate.evidence().evidenceRefs());
        entity.counterEvidence.addAll(candidate.evidence().counterEvidence());
        return entity;
    }

    BugCandidate toDomain() {
        return new BugCandidate(
            new BugCandidate.Identity(candidateId, title, rootCause, confidence, eligibility),
            new BugCandidate.Evidence(sourceLocations, evidenceRefs, counterEvidence),
            new BugCandidate.Recommendation(fixSummary, verificationSummary)
        );
    }
}
