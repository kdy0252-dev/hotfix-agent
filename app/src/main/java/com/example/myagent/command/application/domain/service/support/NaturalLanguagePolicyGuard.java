package com.example.myagent.command.application.domain.service.support;

import java.util.Optional;
import java.util.regex.Pattern;

public final class NaturalLanguagePolicyGuard {
    private static final Pattern POLICY_BYPASS = Pattern.compile(
        "(?i)(ignore (all |the )?(previous|policy)|이전 (지시|정책).*(무시|잊어))"
    );
    private static final Pattern FORBIDDEN_WRITE = Pattern.compile(
        "(?i)((merge|deploy|release|tag|머지|배포|릴리스|태그).*(해|실행|진행|create|run)|"
            + "(해줘|실행해|진행해).*(merge|deploy|release|tag|머지|배포|릴리스|태그))"
    );
    private static final Pattern ARBITRARY_CAPABILITY = Pattern.compile(
        "(?i)(https?://|\\bcurl\\b|\\bshell\\b|\\bpromql\\b|\\blogql\\b|임의 쿼리)"
    );
    private static final Pattern WRONG_SCOPE = Pattern.compile("(?i)\\b(us|usa|asia)[-_ ]?app\\b");

    private NaturalLanguagePolicyGuard() {
    }

    public static Optional<String> rejectionCode(String redactedText) {
        if (POLICY_BYPASS.matcher(redactedText).find()) {
            return Optional.of("POLICY_BYPASS_REQUESTED");
        }
        if (FORBIDDEN_WRITE.matcher(redactedText).find()) {
            return Optional.of("FORBIDDEN_DELIVERY_REQUESTED");
        }
        if (ARBITRARY_CAPABILITY.matcher(redactedText).find()) {
            return Optional.of("ARBITRARY_CAPABILITY_REQUESTED");
        }
        if (WRONG_SCOPE.matcher(redactedText).find()) {
            return Optional.of("OBSERVABILITY_SCOPE_NOT_ALLOWED");
        }
        return Optional.empty();
    }
}
