package com.example.myagent.global.adapter.in.web;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Adapter
@RestControllerAdvice
public class ApiValidationExceptionHandler {

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class
    })
    public ResponseEntity<Map<String, String>> handleInvalidRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "code", "INVALID_REQUEST",
            "message", "요청 형식, 필수 header 또는 입력값이 유효하지 않습니다."
        ));
    }
}
