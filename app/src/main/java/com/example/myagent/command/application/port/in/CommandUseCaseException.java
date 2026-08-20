package com.example.myagent.command.application.port.in;

public final class CommandUseCaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public CommandUseCaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
