package com.example.myagent.incident.adapter.out.persistence.entity.embeddable;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class HotfixCiStage {
    @Column(name = "stage_id", nullable = false)
    private String stageId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String status;
    @Column(name = "start_time_millis", nullable = false)
    private long startTimeMillis;
    @Column(name = "duration_millis", nullable = false)
    private long durationMillis;
    @Column(columnDefinition = "TEXT")
    private String detail;

    protected HotfixCiStage() {
    }

    public static HotfixCiStage from(HotfixResource.CiStage stage) {
        var entity = new HotfixCiStage();
        entity.stageId = stage.id();
        entity.name = stage.name();
        entity.status = stage.status();
        entity.startTimeMillis = stage.startTimeMillis();
        entity.durationMillis = stage.durationMillis();
        entity.detail = stage.detail();
        return entity;
    }

    public HotfixResource.CiStage toDomain() {
        return new HotfixResource.CiStage(
            stageId,
            name,
            status,
            new HotfixResource.CiTiming(startTimeMillis, durationMillis),
            detail
        );
    }
}
