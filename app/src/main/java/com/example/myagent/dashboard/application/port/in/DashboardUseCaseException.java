package com.example.myagent.dashboard.application.port.in;

public final class DashboardUseCaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public DashboardUseCaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
