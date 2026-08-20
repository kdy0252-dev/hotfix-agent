package com.example.myagent.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveEvidenceRedactorTest {
    private final SensitiveEvidenceRedactor redactor = new SensitiveEvidenceRedactor();

    @Test
    void removesCredentialsAndBusinessIdentifiersBeforeLlmInput() {
        String evidence = """
            Authorization: Bearer secret-token
            {"password":"plain-text","tenantId":"tenant-42","vehicleId":"car-7"}
            jdbc:postgresql://database-user:database-pass@db.internal/fms
            """;

        String result = redactor.redact(evidence);

        assertThat(result)
            .contains("Bearer [REDACTED]", "tenantId", "vehicleId")
            .doesNotContain(
                "secret-token",
                "plain-text",
                "tenant-42",
                "car-7",
                "database-user",
                "database-pass"
            );
    }
}
