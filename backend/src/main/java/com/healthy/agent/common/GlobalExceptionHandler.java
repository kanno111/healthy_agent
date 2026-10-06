package com.healthy.agent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentException(AgentException exception) {
        AgentErrorCode errorCode = exception.errorCode();
        return ResponseEntity.status(errorCode.httpStatus()).body(ApiResponse.failure(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled agent request failure", exception);
        AgentErrorCode errorCode = AgentErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(errorCode.httpStatus()).body(ApiResponse.failure(errorCode));
    }
}

