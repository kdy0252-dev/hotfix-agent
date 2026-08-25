package com.example.myagent.dashboard.application.domain.model.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DashboardViewTest {
    @Test
    void formatsBuildTimestampsForKoreanUsers() {
        var build = new DashboardView.BuildReference(
            "job/PR-1",
            1,
            "FAILURE",
            Instant.parse("2026-08-24T09:41:42.402Z"),
            "https://jenkins.example/job/PR-1/1"
        );

        assertThat(build.timestampKstLabel()).isEqualTo("2026-08-24 18:41:42 KST");
    }

    @Test
    void formatsObservabilityTimestampsForKoreanUsers() {
        var signal = new DashboardView.ObservabilitySignal(
            "STACK_TRACE",
            "운영 로그 에러",
            "오류를 감지했습니다.",
            Instant.parse("2026-08-24T09:41:42.402Z"),
            new DashboardView.SignalReference(null, "detail", "Loki", "https://grafana.example")
        );

        assertThat(signal.occurredAtKstLabel()).isEqualTo("2026-08-24 18:41:42 KST");
    }

    @Test
    void classifiesAnErrorTitleAsErrorEvenWhenTechnicalDetailsContainWarn() {
        var signal = new DashboardView.ObservabilitySignal(
            "STACK_TRACE",
            "EU 앱 운영 로그 에러",
            "운영 로그에서 오류를 감지했습니다.",
            Instant.parse("2026-08-24T00:00:00Z"),
            new DashboardView.SignalReference(
                null,
                "WARN emitted before the ERROR stack trace",
                "Loki",
                "https://grafana.example/explore"
            )
        );

        assertThat(signal.severity()).isEqualTo("ERROR");
    }

    @Test
    void classifiesAWarningTitleAsWarning() {
        var signal = new DashboardView.ObservabilitySignal(
            "STACK_TRACE",
            "EU 앱 운영 로그 경고",
            "운영 로그에서 경고를 감지했습니다.",
            Instant.parse("2026-08-24T00:00:00Z"),
            new DashboardView.SignalReference(
                null,
                "WARN",
                "Loki",
                "https://grafana.example/explore"
            )
        );

        assertThat(signal.severity()).isEqualTo("WARNING");
    }
}
