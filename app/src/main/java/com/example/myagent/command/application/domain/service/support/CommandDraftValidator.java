package com.example.myagent.command.application.domain.service.support;

import com.example.myagent.command.application.domain.model.command.CommandIntent;
import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretationDraft;
import io.vavr.control.Try;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CommandDraftValidator {
    private CommandDraftValidator() {
    }

    public static ValidationResult validate(CommandInterpretationDraft draft) {
        if (hasText(draft.rejectionReason())) {
            return ValidationResult.rejected("MODEL_REJECTED", draft.rejectionReason());
        }

        CommandIntent intent = Try.of(() -> CommandIntent.valueOf(
            valueOrEmpty(draft.intent()).toUpperCase(Locale.ROOT)
        )).getOrNull();
        if (intent == null) {
            return ValidationResult.rejected("UNSUPPORTED_INTENT", "지원하지 않는 작업입니다.");
        }

        var missingFields = new ArrayList<String>();
        addAll(missingFields, draft.missingFields());
        addAll(missingFields, draft.ambiguousFields());
        CommandParameters parameters = parameters(intent, draft.parameters(), missingFields);
        if (!missingFields.isEmpty() || parameters == null) {
            var distinctFields = missingFields.stream().distinct().sorted().toList();
            return ValidationResult.clarification(
                distinctFields,
                distinctFields.stream().map(CommandDraftValidator::questionFor).toList()
            );
        }
        return ValidationResult.ready(new InterpretedCommand(intent, parameters));
    }

    private static CommandParameters parameters(
        CommandIntent intent,
        CommandInterpretationDraft.DraftParameters draft,
        List<String> missingFields
    ) {
        if (draft == null) {
            missingFields.add("parameters");
            return null;
        }
        return switch (intent) {
            case ANALYZE_JENKINS -> jenkinsParameters(draft.jenkins(), missingFields);
            case ANALYZE_OBSERVABILITY -> observabilityParameters(draft.observability(), missingFields);
            case LIST_CANDIDATES -> candidateListParameters(draft.candidate(), missingFields);
            case SELECT_CANDIDATE -> candidateSelectionParameters(draft.candidate(), missingFields);
            case GET_HOTFIX_STATUS -> hotfixStatusParameters(draft.hotfix(), missingFields);
            case REFRESH_CI_STATUS -> ciStatusParameters(draft.hotfix(), missingFields);
        };
    }

    private static CommandParameters jenkinsParameters(
        CommandInterpretationDraft.JenkinsParameters draft,
        List<String> missingFields
    ) {
        if (draft == null) {
            missingFields.add("jenkins");
            return null;
        }
        requireText(draft.jobPath(), "jobPath", missingFields);
        requirePositive(draft.buildNumber(), "buildNumber", missingFields);
        SourceReference source = source(draft.source(), missingFields);
        return missingFields.isEmpty()
            ? new CommandParameters.JenkinsAnalysis(draft.jobPath(), draft.buildNumber(), source) : null;
    }

    private static CommandParameters observabilityParameters(
        CommandInterpretationDraft.ObservabilityParameters draft,
        List<String> missingFields
    ) {
        if (draft == null) {
            missingFields.add("observability");
            return null;
        }
        Instant startAt = instant(draft.startAt(), "startAt", missingFields);
        Instant endAt = instant(draft.endAt(), "endAt", missingFields);
        requireText(draft.environment(), "environment", missingFields);
        SourceReference source = source(draft.source(), missingFields);
        if (startAt != null && endAt != null && !startAt.isBefore(endAt)) {
            missingFields.add("validTimeRange");
        }
        return missingFields.isEmpty()
            ? new CommandParameters.ObservabilityAnalysis(
                startAt, endAt, draft.environment().toLowerCase(Locale.ROOT), source
            ) : null;
    }

    private static CommandParameters candidateListParameters(
        CommandInterpretationDraft.CandidateParameters draft,
        List<String> missingFields
    ) {
        String analysisId = draft == null ? null : draft.analysisId();
        requireText(analysisId, "analysisId", missingFields);
        return missingFields.isEmpty() ? new CommandParameters.CandidateList(analysisId) : null;
    }

    private static CommandParameters candidateSelectionParameters(
        CommandInterpretationDraft.CandidateParameters draft,
        List<String> missingFields
    ) {
        if (draft == null) {
            missingFields.add("candidate");
            return null;
        }
        requireText(draft.analysisId(), "analysisId", missingFields);
        requirePositive(draft.analysisVersion(), "analysisVersion", missingFields);
        requireText(draft.candidateId(), "candidateId", missingFields);
        return missingFields.isEmpty()
            ? new CommandParameters.CandidateSelection(
                draft.analysisId(), draft.analysisVersion(), draft.candidateId()
            ) : null;
    }

    private static CommandParameters hotfixStatusParameters(
        CommandInterpretationDraft.HotfixParameters draft,
        List<String> missingFields
    ) {
        String hotfixId = draft == null ? null : draft.hotfixId();
        requireText(hotfixId, "hotfixId", missingFields);
        return missingFields.isEmpty() ? new CommandParameters.HotfixStatus(hotfixId) : null;
    }

    private static CommandParameters ciStatusParameters(
        CommandInterpretationDraft.HotfixParameters draft,
        List<String> missingFields
    ) {
        String hotfixId = draft == null ? null : draft.hotfixId();
        requireText(hotfixId, "hotfixId", missingFields);
        return missingFields.isEmpty() ? new CommandParameters.CiStatusRefresh(hotfixId) : null;
    }

    private static SourceReference source(
        CommandInterpretationDraft.SourceParameters draft,
        List<String> missingFields
    ) {
        if (draft == null || !hasText(draft.type())) {
            missingFields.add("source");
            return null;
        }
        if ("BRANCH".equalsIgnoreCase(draft.type())) {
            requireText(draft.branch(), "source.branch", missingFields);
            return hasText(draft.branch()) ? new SourceReference.Branch(draft.branch()) : null;
        }
        if ("PULL_REQUEST".equalsIgnoreCase(draft.type()) || "PR".equalsIgnoreCase(draft.type())) {
            requirePositive(draft.pullRequestNumber(), "source.pullRequestNumber", missingFields);
            return draft.pullRequestNumber() != null && draft.pullRequestNumber() > 0
                ? new SourceReference.PullRequest(draft.pullRequestNumber()) : null;
        }
        missingFields.add("source.type");
        return null;
    }

    private static Instant instant(String value, String field, List<String> missingFields) {
        if (!hasText(value)) {
            missingFields.add(field);
            return null;
        }
        Instant instant = Try.of(() -> Instant.parse(value)).getOrNull();
        if (instant == null) {
            missingFields.add(field);
        }
        return instant;
    }

    private static void requireText(String value, String field, List<String> missingFields) {
        if (!hasText(value)) {
            missingFields.add(field);
        }
    }

    private static void requirePositive(Long value, String field, List<String> missingFields) {
        if (value == null || value <= 0) {
            missingFields.add(field);
        }
    }

    private static void addAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values.stream().filter(CommandDraftValidator::hasText).toList());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String questionFor(String field) {
        return field + " 값을 하나로 지정해 주세요.";
    }

    public record ValidationResult(
        InterpretedCommand command,
        List<String> missingFields,
        List<String> questions,
        String rejectionCode,
        String rejectionMessage
    ) {
        private static ValidationResult ready(InterpretedCommand command) {
            return new ValidationResult(command, List.of(), List.of(), null, null);
        }

        private static ValidationResult clarification(List<String> fields, List<String> questions) {
            return new ValidationResult(null, fields, questions, null, null);
        }

        private static ValidationResult rejected(String code, String message) {
            return new ValidationResult(null, List.of(), List.of(), code, message);
        }

        public boolean isReady() {
            return command != null;
        }

        public boolean isRejected() {
            return rejectionCode != null;
        }
    }
}
