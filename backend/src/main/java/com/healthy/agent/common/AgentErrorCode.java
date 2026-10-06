package com.healthy.agent.common;

import org.springframework.http.HttpStatus;

public enum AgentErrorCode {
    UNAUTHORIZED(40100, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "无权访问当前资源", HttpStatus.FORBIDDEN),
    INTERNAL_ERROR(50000, "服务暂时不可用", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    AgentErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
