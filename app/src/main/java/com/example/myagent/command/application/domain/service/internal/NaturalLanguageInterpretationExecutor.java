package com.example.myagent.command.application.domain.service.internal;

import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Decision;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Feedback;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Metadata;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.PolicyPreview;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.domain.service.support.CommandDraftValidator;
import com.example.myagent.command.application.domain.service.support.CommandHash;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort;
import com.example.myagent.command.application.port.out.CommandInterpretationStatePort.StateEntry;
import com.example.myagent.command.application.port.out.NaturalLanguageInterpreterPort;
import com.example.myagent.global.annotation.InternalService;
import io.vavr.control.Try;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.task.TaskExecutor;

@InternalService
public class NaturalLanguageInterpretationExecutor {
    private final NaturalLanguageInterpreterPort interpreterPort;
    private final CommandInterpretationStatePort statePort;
    private final TaskExecutor taskExecutor;
    private final Set<String> activeInterpretations = ConcurrentHashMap.newKeySet();

    public NaturalLanguageInterpretationExecutor(
        NaturalLanguageInterpreterPort interpreterPort,
        CommandInterpretationStatePort statePort,
        TaskExecutor taskExecutor
    ) {
        this.interpreterPort = interpreterPort;
        this.statePort = statePort;
        this.taskExecutor = taskExecutor;
    }

    public void submit(StateEntry entry) {
        String interpretationId = entry.interpretation().metadata().interpretationId();
        if (!activeInterpretations.add(interpretationId)) {
            return;
        }
        Try.run(() -> taskExecutor.execute(() -> Try.run(() -> execute(entry))
                .andFinally(() -> activeInterpretations.remove(interpretationId))
                .get()))
            .onFailure(exception -> activeInterpretations.remove(interpretationId));
    }

    private void execute(StateEntry entry) {
        save(entry, decision(InterpretationStatus.INTERPRETING, null, Feedback.empty(), null));
        Try.of(() -> interpreterPort.interpret(entry.request().redactedText())
                .getOrElseThrow(failure -> new IllegalStateException(failure.message())))
            .map(CommandDraftValidator::validate)
            .map(result -> result.isRejected()
                ? rejected(result.rejectionCode(), result.rejectionMessage())
                : interpreted(result))
            .onSuccess(decision -> save(entry, decision))
            .onFailure(exception -> save(entry, decision(
                InterpretationStatus.FAILED,
                null,
                new Feedback(
                    List.of(),
                    List.of(),
                    "INTERPRETATION_FAILED",
                    "자연어 요청을 해석하지 못했습니다. 다시 요청해 주세요."
                ),
                null
            )));
    }

    private Decision interpreted(CommandDraftValidator.ValidationResult result) {
        if (!result.isReady()) {
            return decision(
                InterpretationStatus.NEEDS_CLARIFICATION,
                null,
                new Feedback(result.missingFields(), result.questions(), null, null),
                null
            );
        }
        var policy = PolicyPreview.fixedPolicy();
        return new Decision(
            InterpretationStatus.READY_FOR_CONFIRMATION,
            result.command(),
            Feedback.empty(),
            policy,
            CommandHash.calculate(result.command(), policy.policyVersion())
        );
    }

    private Decision rejected(String code, String message) {
        return decision(
            InterpretationStatus.REJECTED,
            null,
            new Feedback(List.of(), List.of(), code, message),
            null
        );
    }

    private Decision decision(
        InterpretationStatus status,
        InterpretedCommand command,
        Feedback feedback,
        String commandHash
    ) {
        return new Decision(status, command, feedback, PolicyPreview.fixedPolicy(), commandHash);
    }

    private void save(StateEntry entry, Decision decision) {
        CommandInterpretation current = statePort.findById(
                entry.interpretation().metadata().interpretationId()
            )
            .getOrElseThrow(failure -> new IllegalStateException(failure.message()))
            .orElse(entry.interpretation());
        Metadata metadata = current.metadata();
        var updated = new CommandInterpretation(
            new Metadata(
                metadata.interpretationId(),
                metadata.version() + 1,
                metadata.request(),
                metadata.timing()
            ),
            decision
        );
        statePort.save(new StateEntry(entry.idempotencyKey(), entry.request(), updated))
            .getOrElseThrow(failure -> new IllegalStateException(failure.message()));
    }
}
