package com.healthy.agent.common;

public class AgentException extends RuntimeException {
    private final AgentErrorCode errorCode;

    public AgentException(AgentErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public AgentErrorCode errorCode() {
        return errorCode;
    }
}

