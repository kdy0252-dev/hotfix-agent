package com.example.myagent.dashboard.application.domain.service.internal;

import com.example.myagent.dashboard.application.domain.model.view.DashboardView;
import com.example.myagent.global.annotation.InternalService;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@InternalService
public class NaturalLanguageRequestEnricher {
    private static final Pattern PULL_REQUEST_NUMBER = Pattern.compile(
        "(?i)\\bPR\\D{0,30}(\\d{1,10})"
    );

    public boolean needsFailedPullRequestContext(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String compact = normalized.replaceAll("\\s+", "");
        boolean identifiesBuild = normalized.contains("pr") || normalized.contains("빌드")
            || normalized.contains("build");
        boolean identifiesLatestFailure = normalized.contains("최근")
            && normalized.contains("실패");
        boolean genericChatAnalysis = "분석해줘".equals(compact)
            || "원인분석해줘".equals(compact)
            || ((compact.startsWith("응")
                || compact.startsWith("ㅇㅇ")
                || compact.startsWith("좋아"))
                && compact.endsWith("분석해줘"));
        return (identifiesBuild || identifiesLatestFailure || genericChatAnalysis)
            && (normalized.contains("실패")
                || normalized.contains("분석")
                || normalized.contains("원인")
                || normalized.contains("해석")
                || normalized.contains("analy"));
    }

    public String enrich(
        String text,
        List<DashboardView.FailedPullRequest> failedPullRequests
    ) {
        Long requestedNumber = pullRequestNumber(text);
        return failedPullRequests.stream()
            .filter(item -> requestedNumber == null
                || item.pullRequest().number() == requestedNumber)
            .findFirst()
            .map(this::normalizedRequest)
            .orElse(text);
    }

    private Long pullRequestNumber(String text) {
        var matcher = PULL_REQUEST_NUMBER.matcher(text);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private String normalizedRequest(DashboardView.FailedPullRequest failedPullRequest) {
        return """
            Analyze this Jenkins failed pull request build.
            jobPath: %s
            buildNumber: %d
            source.type: PULL_REQUEST
            source.pullRequestNumber: %d
            """.formatted(
                failedPullRequest.build().jobPath(),
                failedPullRequest.build().number(),
                failedPullRequest.pullRequest().number()
            );
    }
}
