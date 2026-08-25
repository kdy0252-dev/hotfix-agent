package com.example.myagent.command.adapter.out.persistence.entity;

import com.example.myagent.command.application.domain.model.command.CommandIntent;
import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.RequestPayload;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.StateEntry;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "command_interpretations", schema = "hotfix_agent")
public class CommandInterpretationEntity {
    @Id
    @Column(name = "interpretation_id", length = 36)
    private String interpretationId;
    private long version;
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    @Column(name = "request_body_hash", nullable = false)
    private String requestBodyHash;
    @Column(name = "redacted_request_text")
    private String redactedRequestText;
    @Column(name = "request_digest", nullable = false)
    private String requestDigest;
    @Column(name = "redacted_preview", nullable = false)
    private String redactedPreview;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterpretationStatus status;
    @Enumerated(EnumType.STRING)
    private CommandIntent intent;
    @Column(name = "command_hash")
    private String commandHash;
    @Column(name = "rejection_code")
    private String rejectionCode;
    @Column(name = "rejection_message")
    private String rejectionMessage;
    @Column(name = "policy_repository", nullable = false)
    private String policyRepository;
    @Column(name = "policy_service", nullable = false)
    private String policyService;
    @Column(name = "policy_delivery", nullable = false)
    private String policyDelivery;
    @Column(name = "policy_version", nullable = false)
    private String policyVersion;
    @Column(name = "job_path")
    private String jobPath;
    @Column(name = "build_number")
    private Long buildNumber;
    @Column(name = "observation_start_at")
    private Instant observationStartAt;
    @Column(name = "observation_end_at")
    private Instant observationEndAt;
    private String environment;
    @Column(name = "analysis_id")
    private String analysisId;
    @Column(name = "analysis_version")
    private Long analysisVersion;
    @Column(name = "candidate_id")
    private String candidateId;
    @Column(name = "hotfix_id")
    private String hotfixId;
    @Column(name = "source_type")
    private String sourceType;
    @Column(name = "source_branch")
    private String sourceBranch;
    @Column(name = "source_pull_request_number")
    private Long sourcePullRequestNumber;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "command_interpretation_missing_fields",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "interpretation_id")
    )
    @OrderColumn(name = "item_order")
    @Column(name = "value", nullable = false)
    private List<String> missingFields = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "command_interpretation_questions",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "interpretation_id")
    )
    @OrderColumn(name = "item_order")
    @Column(name = "value", nullable = false)
    private List<String> clarificationQuestions = new ArrayList<>();

    protected CommandInterpretationEntity() {
    }

    public static CommandInterpretationEntity from(StateEntry entry) {
        var entity = new CommandInterpretationEntity();
        var interpretation = entry.interpretation();
        var metadata = interpretation.metadata();
        var decision = interpretation.decision();
        final var policy = decision.policy();
        entity.interpretationId = metadata.interpretationId();
        entity.version = metadata.version();
        entity.idempotencyKey = entry.idempotencyKey();
        entity.requestBodyHash = entry.request().bodyHash();
        entity.redactedRequestText = entry.request().redactedText();
        entity.requestDigest = metadata.request().digest();
        entity.redactedPreview = metadata.request().redactedPreview();
        entity.createdAt = metadata.timing().createdAt();
        entity.expiresAt = metadata.timing().expiresAt();
        entity.status = decision.status();
        entity.commandHash = decision.commandHash();
        entity.rejectionCode = decision.feedback().rejectionCode();
        entity.rejectionMessage = decision.feedback().rejectionMessage();
        entity.missingFields.addAll(decision.feedback().missingFields());
        entity.clarificationQuestions.addAll(decision.feedback().clarificationQuestions());
        entity.policyRepository = policy.repository();
        entity.policyService = policy.service();
        entity.policyDelivery = policy.delivery();
        entity.policyVersion = policy.policyVersion();
        entity.storeCommand(decision.command());
        return entity;
    }

    public StateEntry toDomain() {
        var metadata = new CommandInterpretation.Metadata(
            interpretationId,
            version,
            new CommandInterpretation.RequestFingerprint(requestDigest, redactedPreview),
            new CommandInterpretation.Timing(createdAt, expiresAt)
        );
        var feedback = new CommandInterpretation.Feedback(
            missingFields,
            clarificationQuestions,
            rejectionCode,
            rejectionMessage
        );
        var policy = new CommandInterpretation.PolicyPreview(
            policyRepository,
            policyService,
            policyDelivery,
            policyVersion
        );
        var decision = new CommandInterpretation.Decision(
            status,
            restoreCommand(),
            feedback,
            policy,
            commandHash
        );
        return new StateEntry(
            idempotencyKey,
            new RequestPayload(requestBodyHash, redactedRequestText),
            new CommandInterpretation(metadata, decision)
        );
    }

    public void markExecuted() {
        status = InterpretationStatus.EXECUTED;
        commandHash = null;
    }

    private void storeCommand(InterpretedCommand command) {
        if (command == null) {
            return;
        }
        intent = command.intent();
        switch (command.parameters()) {
            case CommandParameters.JenkinsAnalysis jenkins -> {
                jobPath = jenkins.jobPath();
                buildNumber = jenkins.buildNumber();
                storeSource(jenkins.source());
            }
            case CommandParameters.ObservabilityAnalysis observability -> {
                observationStartAt = observability.startAt();
                observationEndAt = observability.endAt();
                environment = observability.environment();
                storeSource(observability.source());
            }
            case CommandParameters.CandidateList candidateList ->
                analysisId = candidateList.analysisId();
            case CommandParameters.CandidateSelection selection -> {
                analysisId = selection.analysisId();
                analysisVersion = selection.analysisVersion();
                candidateId = selection.candidateId();
            }
            case CommandParameters.HotfixStatus hotfixStatus -> hotfixId = hotfixStatus.hotfixId();
            case CommandParameters.CiStatusRefresh refresh -> hotfixId = refresh.hotfixId();
        }
    }

    private InterpretedCommand restoreCommand() {
        return intent == null ? null : new InterpretedCommand(intent, switch (intent) {
            case ANALYZE_JENKINS -> new CommandParameters.JenkinsAnalysis(
                jobPath,
                buildNumber,
                restoreSource()
            );
            case ANALYZE_OBSERVABILITY -> new CommandParameters.ObservabilityAnalysis(
                observationStartAt,
                observationEndAt,
                environment,
                restoreSource()
            );
            case LIST_CANDIDATES -> new CommandParameters.CandidateList(analysisId);
            case SELECT_CANDIDATE -> new CommandParameters.CandidateSelection(
                analysisId,
                analysisVersion,
                candidateId
            );
            case GET_HOTFIX_STATUS -> new CommandParameters.HotfixStatus(hotfixId);
            case REFRESH_CI_STATUS -> new CommandParameters.CiStatusRefresh(hotfixId);
        });
    }

    private void storeSource(SourceReference source) {
        if (source instanceof SourceReference.Branch branch) {
            sourceType = "BRANCH";
            sourceBranch = branch.name();
        } else {
            sourceType = "PULL_REQUEST";
            sourcePullRequestNumber = ((SourceReference.PullRequest) source).number();
        }
    }

    private SourceReference restoreSource() {
        return "BRANCH".equals(sourceType)
            ? new SourceReference.Branch(sourceBranch)
            : new SourceReference.PullRequest(sourcePullRequestNumber);
    }
}
