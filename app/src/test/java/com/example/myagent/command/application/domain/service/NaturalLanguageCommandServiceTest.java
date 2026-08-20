package com.example.myagent.command.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretationDraft;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.port.in.ExecuteNaturalLanguageCommandUseCase.ExecutionCommand;
import com.example.myagent.command.application.port.in.InterpretNaturalLanguageCommandUseCase.InterpretCommand;
import com.example.myagent.command.application.port.in.CommandUseCaseException;
import com.example.myagent.command.application.port.out.CommandFailure;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort;
import com.example.myagent.command.application.port.out.CommandExecutionStatePort;
import com.example.myagent.command.application.port.out.NaturalLanguageInterpreterPort;
import io.vavr.control.Either;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NaturalLanguageCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T01:00:00Z");

    @Test
    void dispatchesOnlyTheConfirmedTypedCommandAndReplaysTheExecutionOnce() {
        var interpretationState = new InMemoryStatePort();
        var executionState = new InMemoryExecutionStatePort();
        var dispatchedCommand = new AtomicReference<InterpretedCommand>();
        var dispatchCalls = new AtomicInteger();
        var service = new NaturalLanguageCommandService(
            new StubInterpreter(readyJenkinsDraft()),
            interpretationState,
            (command, idempotencyKey) -> {
                dispatchedCommand.set(command);
                dispatchCalls.incrementAndGet();
                return Either.right(new CommandExecution.Result(
                    "analysis-1", "ANALYSIS_REQUESTED", "/api/v1/analyses/analysis-1", List.of()
                ));
            },
            executionState,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var interpretation = service.interpret(new InterpretCommand(
            "PR 1285의 빌드 181을 분석해줘",
            "interpret-key"
        ));
        var command = new ExecutionCommand(
            interpretation.metadata().interpretationId(),
            interpretation.metadata().version(),
            interpretation.decision().commandHash(),
            "execution-key"
        );

        var first = service.execute(command);
        var replayed = service.execute(command);

        assertThat(dispatchedCommand.get()).isEqualTo(interpretation.decision().command());
        assertThat(dispatchCalls).hasValue(1);
        assertThat(replayed).isEqualTo(first);
    }

    @Test
    void createsReadyJenkinsInterpretationWithoutStoringSecret() {
        var interpreter = new StubInterpreter(readyJenkinsDraft());
        var service = service(interpreter, new InMemoryStatePort(), NOW);

        var result = service.interpret(new InterpretCommand(
            "PR 1285의 FMS-EU/main 빌드 181 분석해. token=very-secret",
            "request-1"
        ));

        assertThat(result.decision().status()).isEqualTo(InterpretationStatus.READY_FOR_CONFIRMATION);
        assertThat(result.decision().commandHash()).hasSize(64);
        assertThat(result.metadata().timing().expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(result.metadata().request().redactedPreview()).doesNotContain("very-secret");
        assertThat(interpreter.calls).isEqualTo(1);
    }

    @Test
    void rejectsForbiddenDeliveryBeforeCallingEmbabel() {
        var interpreter = new StubInterpreter(readyJenkinsDraft());
        var service = service(interpreter, new InMemoryStatePort(), NOW);

        var result = service.interpret(new InterpretCommand(
            "PR 1285를 분석하고 바로 deploy 실행해줘",
            "request-2"
        ));

        assertThat(result.decision().status()).isEqualTo(InterpretationStatus.REJECTED);
        assertThat(result.decision().feedback().rejectionCode())
            .isEqualTo("FORBIDDEN_DELIVERY_REQUESTED");
        assertThat(result.decision().commandHash()).isNull();
        assertThat(interpreter.calls).isZero();
    }

    @Test
    void requiresClarificationForIncompleteCandidateSelection() {
        var draft = new CommandInterpretationDraft(
            "SELECT_CANDIDATE",
            new CommandInterpretationDraft.DraftParameters(
                null,
                null,
                new CommandInterpretationDraft.CandidateParameters("analysis-1", null, null),
                null
            ),
            List.of(),
            List.of(),
            null
        );
        var service = service(new StubInterpreter(draft), new InMemoryStatePort(), NOW);

        var result = service.interpret(new InterpretCommand("analysis-1 후보를 선택해줘", "request-3"));

        assertThat(result.decision().status()).isEqualTo(InterpretationStatus.NEEDS_CLARIFICATION);
        assertThat(result.decision().feedback().missingFields())
            .containsExactly("analysisVersion", "candidateId");
        assertThat(result.decision().commandHash()).isNull();
    }

    @Test
    void replaysSameRequestAndRejectsDifferentBodyForSameIdempotencyKey() {
        var interpreter = new StubInterpreter(readyJenkinsDraft());
        var statePort = new InMemoryStatePort();
        var service = service(interpreter, statePort, NOW);
        var command = new InterpretCommand("빌드 181과 PR 1285를 분석해줘", "request-4");

        var first = service.interpret(command);
        var replay = service.interpret(command);

        assertThat(replay.metadata().interpretationId())
            .isEqualTo(first.metadata().interpretationId());
        assertThat(interpreter.calls).isEqualTo(1);
        assertThatThrownBy(() -> service.interpret(new InterpretCommand("다른 요청", "request-4")))
            .isInstanceOf(CommandUseCaseException.class)
            .extracting(exception -> ((CommandUseCaseException) exception).code())
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void reportsReadyInterpretationAsExpiredAfterTenMinutes() {
        var statePort = new InMemoryStatePort();
        var initialService = service(new StubInterpreter(readyJenkinsDraft()), statePort, NOW);
        var created = initialService.interpret(new InterpretCommand("빌드 분석", "request-5"));
        var laterService = service(
            new StubInterpreter(readyJenkinsDraft()),
            statePort,
            NOW.plusSeconds(601)
        );

        var result = laterService.get(created.metadata().interpretationId());

        assertThat(result.decision().status()).isEqualTo(InterpretationStatus.EXPIRED);
        assertThat(result.decision().commandHash()).isNull();
    }

    private NaturalLanguageCommandService service(
        NaturalLanguageInterpreterPort interpreterPort,
        CommandInterpretationStatePort statePort,
        Instant now
    ) {
        return new NaturalLanguageCommandService(
            interpreterPort,
            statePort,
            (command, idempotencyKey) -> Either.right(new CommandExecution.Result(
                "resource-1", "ACCEPTED", "/resource-1", List.of()
            )),
            new InMemoryExecutionStatePort(),
            Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private CommandInterpretationDraft readyJenkinsDraft() {
        return new CommandInterpretationDraft(
            "ANALYZE_JENKINS",
            new CommandInterpretationDraft.DraftParameters(
                new CommandInterpretationDraft.JenkinsParameters(
                    "FMS-EU/main",
                    181L,
                    new CommandInterpretationDraft.SourceParameters("PR", null, 1285L)
                ),
                null,
                null,
                null
            ),
            List.of(),
            List.of(),
            null
        );
    }

    private static final class StubInterpreter implements NaturalLanguageInterpreterPort {
        private final CommandInterpretationDraft draft;
        private int calls;

        private StubInterpreter(CommandInterpretationDraft draft) {
            this.draft = draft;
        }

        @Override
        public Either<CommandFailure, CommandInterpretationDraft> interpret(String redactedText) {
            calls++;
            return Either.right(draft);
        }
    }

    private static final class InMemoryStatePort implements CommandInterpretationStatePort {
        private final Map<String, StateEntry> entriesById = new LinkedHashMap<>();

        @Override
        public Either<CommandFailure, CommandInterpretation> save(StateEntry entry) {
            entriesById.put(entry.interpretation().metadata().interpretationId(), entry);
            return Either.right(entry.interpretation());
        }

        @Override
        public Either<CommandFailure, Optional<CommandInterpretation>> findById(
            String interpretationId
        ) {
            return Either.right(Optional.ofNullable(entriesById.get(interpretationId))
                .map(StateEntry::interpretation));
        }

        @Override
        public Either<CommandFailure, Optional<StateEntry>> findByIdempotencyKey(
            String idempotencyKey
        ) {
            return Either.right(entriesById.values().stream()
                .filter(entry -> entry.idempotencyKey().equals(idempotencyKey))
                .findFirst());
        }

        @Override
        public Either<CommandFailure, CommandInterpretation> markExecuted(String interpretationId) {
            StateEntry entry = entriesById.get(interpretationId);
            var interpretation = entry.interpretation();
            var executed = new CommandInterpretation(
                interpretation.metadata(),
                new CommandInterpretation.Decision(
                    InterpretationStatus.EXECUTED,
                    interpretation.decision().command(),
                    interpretation.decision().feedback(),
                    interpretation.decision().policy(),
                    null
                )
            );
            entriesById.put(interpretationId, new StateEntry(
                entry.idempotencyKey(), entry.requestBodyHash(), executed
            ));
            return Either.right(executed);
        }
    }

    private static final class InMemoryExecutionStatePort implements CommandExecutionStatePort {
        private final Map<String, CommandExecution> executions = new LinkedHashMap<>();

        @Override
        public Either<CommandFailure, CommandExecution> save(CommandExecution execution) {
            executions.put(execution.identity().idempotencyKey(), execution);
            return Either.right(execution);
        }

        @Override
        public Either<CommandFailure, Optional<CommandExecution>> findByIdempotencyKey(String key) {
            return Either.right(Optional.ofNullable(executions.get(key)));
        }
    }
}
