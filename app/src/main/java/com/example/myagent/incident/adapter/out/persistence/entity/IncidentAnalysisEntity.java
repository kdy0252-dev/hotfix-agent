package com.example.myagent.incident.adapter.out.persistence.entity;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.port.out.IncidentStatePort.AnalysisEnvelope;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@Entity
@Table(name = "incident_analyses", schema = "hotfix_agent")
public class IncidentAnalysisEntity {
    @Id
    @Column(name = "analysis_id", length = 36)
    private String analysisId;
    private long version;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false)
    private String requestHash;
    @Column(name = "request_type")
    private String requestType;
    @Column(name = "jenkins_job_path")
    private String jenkinsJobPath;
    @Column(name = "jenkins_build_number")
    private Long jenkinsBuildNumber;
    @Column(name = "observation_start_at")
    private OffsetDateTime observationStartAt;
    @Column(name = "observation_end_at")
    private OffsetDateTime observationEndAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "observation_environment")
    private AnalysisRequest.Environment observationEnvironment;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceSpec.Type sourceType;
    @Column(name = "source_branch")
    private String sourceBranch;
    @Column(name = "source_pull_request_id")
    private Long sourcePullRequestId;
    @Column(name = "source_commit")
    private String sourceCommit;
    @Column(name = "destination_branch")
    private String destinationBranch;
    @Column(name = "source_provenance")
    private String sourceProvenance;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisSession.Status status;
    @Column(name = "failure_reason")
    private String failureReason;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("itemOrder ASC")
    private List<IncidentCandidateEntity> candidates = new ArrayList<>();

    protected IncidentAnalysisEntity() {
    }

    public static IncidentAnalysisEntity from(AnalysisEnvelope envelope) {
        var entity = new IncidentAnalysisEntity();
        var session = envelope.session();
        final var source = session.snapshot().source();
        final var revision = session.snapshot().sourceRevision();
        entity.analysisId = session.identity().analysisId();
        entity.version = session.identity().version();
        entity.schemaVersion = envelope.schemaVersion();
        entity.idempotencyKey = envelope.idempotencyKey();
        entity.requestHash = envelope.requestHash();
        entity.storeRequest(envelope.request());
        entity.sourceType = source.type();
        entity.sourceBranch = source.branchName();
        entity.sourcePullRequestId = source.pullRequestId();
        if (revision != null) {
            entity.sourceCommit = revision.commit();
            entity.destinationBranch = revision.destinationBranch();
            entity.sourceProvenance = revision.provenance();
        }
        entity.createdAt = session.snapshot().createdAt();
        entity.expiresAt = session.snapshot().expiresAt();
        entity.status = session.result().status();
        entity.failureReason = session.result().failureReason();
        entity.updatedAt = Instant.now();
        IntStream.range(0, session.result().candidates().size())
            .mapToObj(index -> IncidentCandidateEntity.from(
                session.result().candidates().get(index),
                entity,
                index
            ))
            .forEach(entity.candidates::add);
        return entity;
    }

    public AnalysisEnvelope toDomain() {
        var source = new SourceSpec(sourceType, sourceBranch, sourcePullRequestId);
        var revision = sourceCommit == null
            ? null : new SourceRevision(sourceCommit, destinationBranch, sourceProvenance);
        var session = new AnalysisSession(
            new AnalysisSession.Identity(analysisId, version, requestHash),
            new AnalysisSession.Snapshot(source, revision, createdAt, expiresAt),
            new AnalysisSession.Result(
                status,
                candidates.stream().map(IncidentCandidateEntity::toDomain).toList(),
                failureReason
            )
        );
        return new AnalysisEnvelope(
            schemaVersion,
            idempotencyKey,
            requestHash,
            session,
            restoreRequest(source)
        );
    }

    private void storeRequest(AnalysisRequest request) {
        if (request instanceof AnalysisRequest.Jenkins jenkins) {
            requestType = "JENKINS";
            jenkinsJobPath = jenkins.jobPath();
            jenkinsBuildNumber = jenkins.buildNumber();
        } else if (request instanceof AnalysisRequest.Observability observability) {
            requestType = "OBSERVABILITY";
            observationStartAt = observability.timeRange().startAt();
            observationEndAt = observability.timeRange().endAt();
            observationEnvironment = observability.environment();
        }
    }

    private AnalysisRequest restoreRequest(SourceSpec source) {
        if ("JENKINS".equals(requestType)) {
            return new AnalysisRequest.Jenkins(jenkinsJobPath, jenkinsBuildNumber, source);
        }
        if ("OBSERVABILITY".equals(requestType)) {
            return new AnalysisRequest.Observability(
                new AnalysisRequest.TimeRange(observationStartAt, observationEndAt),
                observationEnvironment,
                source
            );
        }
        return null;
    }
}
