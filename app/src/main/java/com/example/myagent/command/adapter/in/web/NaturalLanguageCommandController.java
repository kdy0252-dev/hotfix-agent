package com.example.myagent.command.adapter.in.web;

import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.port.in.ExecuteNaturalLanguageCommandUseCase;
import com.example.myagent.command.application.port.in.ExecuteNaturalLanguageCommandUseCase.ExecutionCommand;
import com.example.myagent.command.application.port.in.GetCommandInterpretationUseCase;
import com.example.myagent.command.application.port.in.InterpretNaturalLanguageCommandUseCase;
import com.example.myagent.command.application.port.in.InterpretNaturalLanguageCommandUseCase.InterpretCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Adapter
@RestController
@RequestMapping("/api/v1/natural-language/interpretations")
@Tag(name = "Natural-language commands")
@Validated
public class NaturalLanguageCommandController {
    private final InterpretNaturalLanguageCommandUseCase interpretUseCase;
    private final GetCommandInterpretationUseCase getUseCase;
    private final ExecuteNaturalLanguageCommandUseCase executeUseCase;

    public NaturalLanguageCommandController(
        InterpretNaturalLanguageCommandUseCase interpretUseCase,
        GetCommandInterpretationUseCase getUseCase,
        ExecuteNaturalLanguageCommandUseCase executeUseCase
    ) {
        this.interpretUseCase = interpretUseCase;
        this.getUseCase = getUseCase;
        this.executeUseCase = executeUseCase;
    }

    @PostMapping
    @Operation(summary = "Interpret a natural-language request without executing it")
    public ResponseEntity<CommandInterpretation> interpret(
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody InterpretationRequest request
    ) {
        var interpretation = interpretUseCase.interpret(
            new InterpretCommand(request.text(), idempotencyKey)
        );
        URI location = URI.create(
            "/api/v1/natural-language/interpretations/"
                + interpretation.metadata().interpretationId()
        );
        return ResponseEntity.created(location).body(interpretation);
    }

    @GetMapping("/{interpretationId}")
    @Operation(summary = "Get an interpretation preview and its current expiry state")
    public ResponseEntity<CommandInterpretation> get(
        @PathVariable @NotBlank String interpretationId
    ) {
        return ResponseEntity.ok(getUseCase.get(interpretationId));
    }

    @PostMapping("/{interpretationId}/executions")
    @Operation(summary = "Confirm and delegate a typed command to the structured use cases")
    public ResponseEntity<CommandExecution> execute(
        @PathVariable @NotBlank String interpretationId,
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody ExecutionRequest request
    ) {
        var execution = executeUseCase.execute(new ExecutionCommand(
            interpretationId,
            request.interpretationVersion(),
            request.commandHash(),
            idempotencyKey
        ));
        return ResponseEntity.accepted().body(execution);
    }

    public record InterpretationRequest(
        @NotBlank
        @Size(max = 2_000)
        String text
    ) {
    }

    public record ExecutionRequest(
        @Positive long interpretationVersion,
        @NotBlank String commandHash
    ) {
    }
}
