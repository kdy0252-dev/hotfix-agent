package com.example.myagent.incident.adapter.out.persistence.entity;

import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "candidate_refinement_tasks", schema = "hotfix_agent")
public class CandidateRefinementTaskEntity {
    @Id
    @Column(name = "task_id", length = 100)
    private String taskId;
    @Column(name = "analysis_id", nullable = false, length = 36)
    private String analysisId;
    @Column(name = "candidate_id", nullable = false, length = 100)
    private String candidateId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CandidateRefinementTask.Status status;
    @Column(name = "failure_reason", length = 1000)
    private String failureReason;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CandidateRefinementTaskEntity() {
    }

    public static CandidateRefinementTaskEntity from(CandidateRefinementTask task) {
        var entity = new CandidateRefinementTaskEntity();
        entity.taskId = task.taskId();
        entity.analysisId = task.analysisId();
        entity.candidateId = task.candidateId();
        entity.status = task.status();
        entity.failureReason = task.failureReason();
        entity.requestedAt = task.requestedAt();
        entity.updatedAt = task.updatedAt();
        return entity;
    }

    public CandidateRefinementTask toDomain() {
        return new CandidateRefinementTask(
            taskId,
            analysisId,
            candidateId,
            status,
            failureReason,
            requestedAt,
            updatedAt
        );
    }
}
