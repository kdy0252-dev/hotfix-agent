package com.example.myagent.incident.adapter.out.persistence.entity.embeddable;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class HotfixVerificationStage {
    @Column(nullable = false)
    private String name;
    @Column(name = "exit_code", nullable = false)
    private int exitCode;
    @Column(nullable = false)
    private boolean required;
    @Column(columnDefinition = "TEXT")
    private String summary;

    protected HotfixVerificationStage() {
    }

    public static HotfixVerificationStage from(HotfixResource.StageResult stage) {
        var entity = new HotfixVerificationStage();
        entity.name = stage.name();
        entity.exitCode = stage.exitCode();
        entity.required = stage.required();
        entity.summary = stage.summary();
        return entity;
    }

    public HotfixResource.StageResult toDomain() {
        return new HotfixResource.StageResult(name, exitCode, required, summary);
    }
}
