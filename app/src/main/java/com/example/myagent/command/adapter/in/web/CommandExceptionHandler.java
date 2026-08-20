package com.example.myagent.command.adapter.in.web;

import com.example.myagent.command.application.port.in.CommandUseCaseException;
import java.util.Map;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Adapter
@RestControllerAdvice(assignableTypes = NaturalLanguageCommandController.class)
public class CommandExceptionHandler {

    @ExceptionHandler(CommandUseCaseException.class)
    public ResponseEntity<Map<String, String>> handle(CommandUseCaseException exception) {
        HttpStatus status = status(exception.code());
        return ResponseEntity.status(status).body(Map.of(
            "code", exception.code(),
            "message", exception.getMessage()
        ));
    }

    private HttpStatus status(String code) {
        return switch (code) {
            case "INTERPRETATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_KEY_REUSED", "INTERPRETATION_NOT_READY",
                "INTERPRETATION_EXPIRED", "INTERPRETATION_VERSION_MISMATCH",
                "COMMAND_HASH_MISMATCH" -> HttpStatus.CONFLICT;
            case "INVALID_TEXT", "TEXT_TOO_LONG", "IDEMPOTENCY_KEY_REQUIRED",
                "INVALID_EXECUTION_CONFIRMATION" -> HttpStatus.BAD_REQUEST;
            case "COMMAND_INTERPRETATION_FAILED", "STRUCTURED_COMMAND_FAILED" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
