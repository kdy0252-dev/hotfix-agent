package com.example.myagent.global.support;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SensitiveEvidenceRedactor {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile(
        "(?i)\\bbearer\\s+[a-z0-9._~+/=-]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)(\\\"?(?:cookie|set-cookie|password|passwd|token|"
            + "api[_-]?key|secret)\\\"?\\s*[:=]\\s*)"
            + "(\\\"[^\\\"]*\\\"|'[^']*'|[^,\\s}\\]]+)"
    );
    private static final Pattern IDENTIFIER_ASSIGNMENT = Pattern.compile(
        "(?i)(\\\"?(?:tenantId|userId|vehicleId|vin)\\\"?\\s*[:=]\\s*)"
            + "(\\\"[^\\\"]*\\\"|'[^']*'|[^,\\s}\\]]+)"
    );
    private static final Pattern URI_CREDENTIALS = Pattern.compile(
        "(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@"
    );

    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1\"" + REDACTED + "\"");
        redacted = IDENTIFIER_ASSIGNMENT.matcher(redacted).replaceAll("$1\"" + REDACTED + "\"");
        return URI_CREDENTIALS.matcher(redacted).replaceAll("$1" + REDACTED + "@");
    }
}
