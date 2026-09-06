package com.thinhbui303.observability.core.api.exception;

public class BadRequestException extends RuntimeException {
    private final String code;
    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}