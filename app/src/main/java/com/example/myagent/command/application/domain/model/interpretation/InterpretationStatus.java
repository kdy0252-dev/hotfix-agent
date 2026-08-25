package com.example.myagent.command.application.domain.model.interpretation;

public enum InterpretationStatus {
    INTERPRETATION_REQUESTED,
    INTERPRETING,
    READY_FOR_CONFIRMATION,
    NEEDS_CLARIFICATION,
    REJECTED,
    EXPIRED,
    EXECUTED,
    FAILED
}
