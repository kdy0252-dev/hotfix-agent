package com.example.myagent.command.adapter.out.persistence.entity;

import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "command_executions", schema = "hotfix_agent")
public class CommandExecutionEntity {
    @Id
    @Column(name = "execution_id", length = 36)
    private String executionId;
    @Column(name = "interpretation_id", nullable = false)
    private String interpretationId;
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false)
    private String requestHash;
    @Column(name = "resource_id")
    private String resourceId;
    @Column(nullable = false)
    private String status;
    @Column(name = "status_url")
    private String statusUrl;
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "command_execution_items",
        schema = "hotfix_agent",
        joinColumns = @JoinColumn(name = "execution_id")
    )
    @OrderColumn(name = "item_order")
    @Column(name = "value", nullable = false)
    private List<String> itemIds = new ArrayList<>();

    protected CommandExecutionEntity() {
    }

    public static CommandExecutionEntity from(CommandExecution execution) {
        var entity = new CommandExecutionEntity();
        entity.executionId = execution.identity().executionId();
        entity.interpretationId = execution.identity().interpretationId();
        entity.idempotencyKey = execution.identity().idempotencyKey();
        entity.requestHash = execution.identity().requestHash();
        entity.resourceId = execution.result().resourceId();
        entity.status = execution.result().status();
        entity.statusUrl = execution.result().statusUrl();
        entity.executedAt = execution.executedAt();
        entity.itemIds.addAll(execution.result().itemIds());
        return entity;
    }

    public CommandExecution toDomain() {
        return new CommandExecution(
            new CommandExecution.Identity(
                executionId,
                interpretationId,
                idempotencyKey,
                requestHash
            ),
            new CommandExecution.Result(resourceId, status, statusUrl, itemIds),
            executedAt
        );
    }
}
