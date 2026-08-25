package com.example.myagent.command.application.domain.service;

import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Decision;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Feedback;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Metadata;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.PolicyPreview;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.RequestFingerprint;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Timing;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.domain.service.internal.NaturalLanguageInterpretationExecutor;
import com.example.myagent.command.application.domain.service.support.CommandHash;
import com.example.myagent.command.application.domain.service.support.NaturalLanguagePolicyGuard;
import com.example.myagent.command.application.domain.service.support.SensitiveTextRedactor;
import com.example.myagent.command.application.domain.service.support.TextDigest;
import com.example.myagent.command.application.port.in.CommandUseCaseException;
import com.example.myagent.command.application.port.in.ExecuteNaturalLanguageCommandUseCase;
import com.example.myagent.command.application.port.in.GetCommandInterpretationUseCase;
import com.example.myagent.command.application.port.in.InterpretNaturalLanguageCommandUseCase;
import com.example.myagent.command.application.port.in.RecoverNaturalLanguageInterpretationUseCase;
import com.example.myagent.command.application.port.out.CommandExecutionStatePort;
import com.example.myagent.command.application.port.out.CommandFailure;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.RequestPayload;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.StateEntry;
import com.example.myagent.command.application.port.out.ConfirmedCommandDispatchPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NaturalLanguageCommandService implements InterpretNaturalLanguageCommandUseCase,
    GetCommandInterpretationUseCase, ExecuteNaturalLanguageCommandUseCase,
    RecoverNaturalLanguageInterpretationUseCase {
    private static final Duration INTERPRETATION_TTL = Duration.ofMinutes(10);

    private final CommandInterpretationStatePort statePort;
    private final ConfirmedCommandDispatchPort dispatchPort;
    private final CommandExecutionStatePort executionStatePort;
    private final Clock clock;
    private final NaturalLanguageInterpretationExecutor interpretationExecutor;

    public NaturalLanguageCommandService(
        CommandInterpretationStatePort statePort,
        ConfirmedCommandDispatchPort dispatchPort,
        CommandExecutionStatePort executionStatePort,
        Clock clock,
        NaturalLanguageInterpretationExecutor interpretationExecutor
    ) {
        this.statePort = statePort;
        this.dispatchPort = dispatchPort;
        this.executionStatePort = executionStatePort;
        this.clock = clock;
        this.interpretationExecutor = interpretationExecutor;
    }

    @Override
    public CommandInterpretation interpret(InterpretCommand command) {
        validateInput(command);
        String requestBodyHash = TextDigest.sha256(command.text());
        var previousEntry = statePort.findByIdempotencyKey(command.idempotencyKey())
            .getOrElseThrow(this::failure);
        if (previousEntry.isPresent()) {
            return replay(previousEntry.get(), requestBodyHash);
        }

        String redactedText = SensitiveTextRedactor.redact(command.text());
        var fingerprint = new RequestFingerprint(
            TextDigest.sha256(redactedText),
            SensitiveTextRedactor.preview(redactedText)
        );
        var policyRejection = NaturalLanguagePolicyGuard.rejectionCode(redactedText);
        CommandInterpretation interpretation = policyRejection
            .map(code -> rejected(fingerprint, code, "허용된 자연어 작업 범위를 벗어났습니다."))
            .orElseGet(() -> create(
                fingerprint,
                new Decision(
                    InterpretationStatus.INTERPRETATION_REQUESTED,
                    null,
                    Feedback.empty(),
                    PolicyPreview.fixedPolicy(),
                    null
                )
            ));

        var entry = new StateEntry(
            command.idempotencyKey(),
            new RequestPayload(requestBodyHash, redactedText),
            interpretation
        );
        CommandInterpretation saved = statePort.save(entry).getOrElseThrow(this::failure);
        if (policyRejection.isEmpty()) {
            interpretationExecutor.submit(entry);
        }
        return saved;
    }

    @Override
    public int recoverInterruptedInterpretations() {
        var entries = statePort.findIncomplete().getOrElseThrow(this::failure);
        entries.forEach(interpretationExecutor::submit);
        return entries.size();
    }

    @Override
    public CommandInterpretation get(String interpretationId) {
        var interpretation = statePort.findById(interpretationId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> new CommandUseCaseException(
                "INTERPRETATION_NOT_FOUND", "자연어 해석 결과를 찾을 수 없습니다."
            ));
        return expireIfNecessary(interpretation);
    }

    @Override
    public synchronized CommandExecution execute(ExecutionCommand command) {
        validateExecutionInput(command);
        String requestHash = TextDigest.sha256(
            command.interpretationId() + '|' + command.interpretationVersion() + '|'
                + command.commandHash()
        );
        var previous = executionStatePort.findByIdempotencyKey(command.idempotencyKey())
            .getOrElseThrow(this::failure);
        if (previous.isPresent()) {
            return replayExecution(previous.get(), requestHash);
        }
        CommandInterpretation interpretation = executableInterpretation(command);
        var result = dispatchPort.dispatch(
            interpretation.decision().command(),
            command.idempotencyKey()
        ).getOrElseThrow(this::failure);
        var execution = new CommandExecution(
            new CommandExecution.Identity(
                UUID.randomUUID().toString(),
                command.interpretationId(),
                command.idempotencyKey(),
                requestHash
            ),
            result,
            clock.instant()
        );
        CommandExecution saved = executionStatePort.save(execution).getOrElseThrow(this::failure);
        statePort.markExecuted(command.interpretationId()).getOrElseThrow(this::failure);
        return saved;
    }

    private CommandInterpretation executableInterpretation(ExecutionCommand command) {
        CommandInterpretation interpretation = statePort.findById(command.interpretationId())
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> new CommandUseCaseException(
                "INTERPRETATION_NOT_FOUND",
                "자연어 해석 결과를 찾을 수 없습니다."
            ));
        if (interpretation.decision().status() != InterpretationStatus.READY_FOR_CONFIRMATION) {
            throw new CommandUseCaseException("INTERPRETATION_NOT_READY", "실행 가능한 해석 상태가 아닙니다.");
        }
        if (!clock.instant().isBefore(interpretation.metadata().timing().expiresAt())) {
            throw new CommandUseCaseException("INTERPRETATION_EXPIRED", "자연어 해석 결과가 만료되었습니다.");
        }
        if (interpretation.metadata().version() != command.interpretationVersion()) {
            throw new CommandUseCaseException("INTERPRETATION_VERSION_MISMATCH", "해석 version이 일치하지 않습니다.");
        }
        String recomputedHash = CommandHash.calculate(
            interpretation.decision().command(),
            interpretation.decision().policy().policyVersion()
        );
        if (!constantTimeEquals(recomputedHash, interpretation.decision().commandHash())
            || !constantTimeEquals(recomputedHash, command.commandHash())) {
            throw new CommandUseCaseException("COMMAND_HASH_MISMATCH", "command hash가 일치하지 않습니다.");
        }
        return interpretation;
    }

    private CommandExecution replayExecution(CommandExecution execution, String requestHash) {
        if (!execution.identity().requestHash().equals(requestHash)) {
            throw new CommandUseCaseException(
                "IDEMPOTENCY_KEY_REUSED",
                "같은 idempotency key에 다른 실행 요청을 사용할 수 없습니다."
            );
        }
        return execution;
    }

    private void validateExecutionInput(ExecutionCommand command) {
        if (command == null || command.interpretationVersion() <= 0
            || command.commandHash() == null || command.commandHash().isBlank()) {
            throw new CommandUseCaseException("INVALID_EXECUTION_CONFIRMATION", "version과 commandHash가 필요합니다.");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new CommandUseCaseException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다.");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private CommandInterpretation rejected(
        RequestFingerprint fingerprint,
        String rejectionCode,
        String rejectionMessage
    ) {
        return create(
            fingerprint,
            new Decision(
                InterpretationStatus.REJECTED,
                null,
                new Feedback(List.of(), List.of(), rejectionCode, rejectionMessage),
                PolicyPreview.fixedPolicy(),
                null
            )
        );
    }

    private CommandInterpretation create(RequestFingerprint fingerprint, Decision decision) {
        Instant createdAt = clock.instant();
        return new CommandInterpretation(
            new Metadata(
                UUID.randomUUID().toString(),
                1L,
                fingerprint,
                new Timing(createdAt, createdAt.plus(INTERPRETATION_TTL))
            ),
            decision
        );
    }

    private CommandInterpretation expireIfNecessary(CommandInterpretation interpretation) {
        if (interpretation.decision().status() != InterpretationStatus.READY_FOR_CONFIRMATION
            || clock.instant().isBefore(interpretation.metadata().timing().expiresAt())) {
            return interpretation;
        }
        var expiredDecision = new Decision(
            InterpretationStatus.EXPIRED,
            interpretation.decision().command(),
            interpretation.decision().feedback(),
            interpretation.decision().policy(),
            null
        );
        return new CommandInterpretation(interpretation.metadata(), expiredDecision);
    }

    private CommandInterpretation replay(StateEntry previousEntry, String requestBodyHash) {
        if (!previousEntry.request().bodyHash().equals(requestBodyHash)) {
            throw new CommandUseCaseException(
                "IDEMPOTENCY_KEY_REUSED", "같은 idempotency key에 다른 요청 본문을 사용할 수 없습니다."
            );
        }
        return expireIfNecessary(previousEntry.interpretation());
    }

    private void validateInput(InterpretCommand command) {
        if (command == null || command.text() == null || command.text().isBlank()) {
            throw new CommandUseCaseException("INVALID_TEXT", "text는 비어 있을 수 없습니다.");
        }
        if (command.text().length() > 2_000) {
            throw new CommandUseCaseException("TEXT_TOO_LONG", "text는 2,000자를 넘을 수 없습니다.");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new CommandUseCaseException(
                "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다."
            );
        }
    }

    private CommandUseCaseException failure(CommandFailure commandFailure) {
        return new CommandUseCaseException(commandFailure.code(), commandFailure.message());
    }
}
