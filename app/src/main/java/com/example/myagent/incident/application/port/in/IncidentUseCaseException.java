package com.example.myagent.incident.application.port.in;

public final class IncidentUseCaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public IncidentUseCaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
