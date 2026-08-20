package com.example.myagent.incident.adapter.in.web;

import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import java.util.Map;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Adapter
@RestControllerAdvice(assignableTypes = IncidentController.class)
public class IncidentExceptionHandler {

    @ExceptionHandler(IncidentUseCaseException.class)
    public ResponseEntity<Map<String, String>> handle(IncidentUseCaseException exception) {
        return ResponseEntity.status(status(exception.code())).body(Map.of(
            "code", exception.code(),
            "message", exception.getMessage()
        ));
    }

    private HttpStatus status(String code) {
        return switch (code) {
            case "ANALYSIS_NOT_FOUND", "CANDIDATE_NOT_FOUND", "HOTFIX_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_KEY_REUSED", "SOURCE_REVISION_MISMATCH", "STALE_ANALYSIS",
                "STALE_SOURCE", "ANALYSIS_EXPIRED" -> HttpStatus.CONFLICT;
            case "JENKINS_BUILD_NOT_ELIGIBLE", "CANDIDATE_NOT_ELIGIBLE",
                "REPORT_ONLY_MODE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "IDEMPOTENCY_KEY_REQUIRED", "INVALID_ANALYSIS_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "JENKINS_READ_FAILED", "GRAFANA_READ_FAILED", "SOURCE_RESOLUTION_FAILED" ->
                HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
