package com.example.myagent.command.application.domain.service.support;

import java.util.regex.Pattern;

public final class SensitiveTextRedactor {
    private static final Pattern BEARER_TOKEN = Pattern.compile(
        "(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s,;]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)(token|password|api[_-]?key)\\s*[=:]\\s*[^\\s,;]+"
    );

    private SensitiveTextRedactor() {
    }

    public static String redact(String text) {
        String bearerRedacted = BEARER_TOKEN.matcher(text).replaceAll("$1[REDACTED]");
        return SECRET_ASSIGNMENT.matcher(bearerRedacted).replaceAll("$1=[REDACTED]");
    }

    public static String preview(String redactedText) {
        int endIndex = Math.min(redactedText.length(), 160);
        return redactedText.substring(0, endIndex);
    }
}
