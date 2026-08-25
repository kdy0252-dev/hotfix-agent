package com.example.myagent.incident.adapter.out.persistence.entity;

import com.example.myagent.incident.adapter.out.persistence.entity.embeddable.HotfixCiStage;
import com.example.myagent.incident.adapter.out.persistence.entity.embeddable.HotfixVerificationStage;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "incident_hotfixes", schema = "hotfix_agent")
public class IncidentHotfixEntity {
    @Id
    @Column(name = "hotfix_id", length = 36)
    private String hotfixId;
    @Column(name = "analysis_id", nullable = false)
    private String analysisId;
    @Column(name = "candidate_id", nullable = false)
    private String candidateId;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false)
    private String requestHash;
    @Column(name = "patch_instruction", columnDefinition = "TEXT")
    private String patchInstruction;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HotfixResource.Status status;
    @Column(name = "branch_name")
    private String branchName;
    @Column(name = "changed_files", nullable = false)
    private int changedFiles;
    @Column(name = "changed_lines", nullable = false)
    private int changedLines;
    @Column(name = "focused_attempts", nullable = false)
    private int focusedAttempts;
    @Column(name = "base_commit")
    private String baseCommit;
    @Column(name = "patch_commit")
    private String patchCommit;
    @Column(name = "jenkinsfile_path")
    private String jenkinsfilePath;
    @Column(name = "jenkinsfile_sha256")
    private String jenkinsfileSha256;
    @Column(name = "jenkinsfile_profile_version")
    private Integer jenkinsfileProfileVersion;
    @Column(name = "human_review_reason")
    private String humanReviewReason;
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage")
    private HotfixResource.WorkflowStage failureStage;
    @Column(name = "failure_code")
    private String failureCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "active_stage")
    private HotfixResource.WorkflowStage activeStage;
    @Column(name = "active_message", columnDefinition = "TEXT")
    private String activeMessage;
    @Column(name = "review_branch_url")
    private String reviewBranchUrl;
    @Column(name = "draft_pull_request_url")
    private String draftPullRequestUrl;
    @Column(name = "ci_build_url")
    private String ciBuildUrl;
    @Column(name = "ci_result")
    private String ciResult;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(
        name = "incident_hotfix_verification_stages",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "hotfix_id")
    )
    @OrderColumn(name = "item_order")
    private List<HotfixVerificationStage> stages = new ArrayList<>();

    @ElementCollection
    @CollectionTable(
        name = "incident_hotfix_ci_stages",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "hotfix_id")
    )
    @OrderColumn(name = "item_order")
    private List<HotfixCiStage> ciStages = new ArrayList<>();

    protected IncidentHotfixEntity() {
    }

    public static IncidentHotfixEntity from(HotfixEnvelope envelope) {
        var entity = new IncidentHotfixEntity();
        var resource = envelope.resource();
        var progress = resource.progress();
        var verification = progress.verification();
        final var provenance = verification.provenance();
        entity.hotfixId = resource.identity().hotfixId();
        entity.analysisId = resource.identity().analysisId();
        entity.candidateId = resource.identity().candidateId();
        entity.schemaVersion = envelope.schemaVersion();
        entity.idempotencyKey = envelope.idempotencyKey();
        entity.requestHash = envelope.requestHash();
        entity.patchInstruction = resource.patchInstruction().present()
            ? resource.patchInstruction().text() : null;
        entity.status = progress.status();
        entity.branchName = progress.branchName();
        entity.changedFiles = progress.changedFiles();
        entity.changedLines = progress.changedLines();
        entity.focusedAttempts = verification.focusedAttempts();
        entity.humanReviewReason = progress.humanReviewReason();
        entity.failureStage = progress.failure() == null ? null : progress.failure().stage();
        entity.failureCode = progress.failure() == null ? null : progress.failure().code();
        entity.activeStage = progress.activity() == null ? null : progress.activity().stage();
        entity.activeMessage = progress.activity() == null ? null : progress.activity().message();
        entity.reviewBranchUrl = resource.publication().reviewBranchUrl();
        entity.storeProvenance(provenance);
        entity.stages.addAll(verification.stages().stream()
            .map(HotfixVerificationStage::from).toList());
        entity.draftPullRequestUrl = resource.publication().draftPullRequestUrl();
        entity.ciBuildUrl = resource.publication().ciBuildUrl();
        entity.ciResult = resource.publication().ciResult();
        entity.ciStages.addAll(resource.publication().ciStages().stream()
            .map(HotfixCiStage::from).toList());
        entity.updatedAt = envelope.updatedAt() == null ? Instant.now() : envelope.updatedAt();
        return entity;
    }

    public HotfixEnvelope toDomain() {
        var provenance = baseCommit == null && patchCommit == null && jenkinsfilePath == null
            ? null : new HotfixResource.VerificationProvenance(
                baseCommit,
                patchCommit,
                restoreJenkinsfile()
            );
        var verification = new HotfixResource.Verification(
            focusedAttempts,
            provenance,
            stages.stream().map(HotfixVerificationStage::toDomain).toList()
        );
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                status,
                branchName,
                activeStage == null ? null : new HotfixResource.ExecutionDetail(
                    activeStage,
                    activeMessage
                ),
                humanReviewReason == null ? null : new HotfixResource.FailureDetail(
                    failureStage,
                    failureCode,
                    humanReviewReason
                )
            ),
            new HotfixResource.ChangeMetrics(changedFiles, changedLines),
            verification
        );
        var publication = new HotfixResource.Publication(
            reviewBranchUrl,
            draftPullRequestUrl,
            ciBuildUrl,
            new HotfixResource.CiPipeline(
                ciResult,
                ciStages.stream().map(HotfixCiStage::toDomain).toList()
            )
        );
        var resource = new HotfixResource(
            new HotfixResource.Identity(hotfixId, analysisId, candidateId),
            HotfixResource.PatchInstruction.from(patchInstruction),
            progress,
            publication
        );
        return new HotfixEnvelope(schemaVersion, idempotencyKey, requestHash, resource, updatedAt);
    }

    private void storeProvenance(HotfixResource.VerificationProvenance provenance) {
        if (provenance == null) {
            return;
        }
        baseCommit = provenance.baseCommit();
        patchCommit = provenance.patchCommit();
        if (provenance.jenkinsfile() != null) {
            jenkinsfilePath = provenance.jenkinsfile().path();
            jenkinsfileSha256 = provenance.jenkinsfile().sha256();
            jenkinsfileProfileVersion = provenance.jenkinsfile().profileVersion();
        }
    }

    private HotfixResource.JenkinsfileProfile restoreJenkinsfile() {
        return jenkinsfilePath == null ? null : new HotfixResource.JenkinsfileProfile(
            jenkinsfilePath,
            jenkinsfileSha256,
            jenkinsfileProfileVersion
        );
    }
}
