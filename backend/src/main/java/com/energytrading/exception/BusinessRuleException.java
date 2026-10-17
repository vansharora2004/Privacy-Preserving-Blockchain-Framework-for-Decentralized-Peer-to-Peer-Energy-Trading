package com.energytrading.exception;

import org.springframework.http.HttpStatus;

/** A request that is well-formed but not allowed in the current state (mapped to its HTTP status). */
public class BusinessRuleException extends RuntimeException {

    private final HttpStatus status;

    public BusinessRuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BusinessRuleException conflict(String message) {
        return new BusinessRuleException(HttpStatus.CONFLICT, message);
    }

    public static BusinessRuleException badRequest(String message) {
        return new BusinessRuleException(HttpStatus.BAD_REQUEST, message);
    }
}
