package com.thinhbui303.observability.common;

import java.time.Instant;

public class ErrorResponse {
    private String code;
    private String message;
    private Instant timestamp;
    private String path;
    private String traceId;

    public ErrorResponse(String code, String message, Instant timestamp, String path, String traceId) {
        this.code = code;
        this.message = message;
        this.timestamp = timestamp;
        this.path = path;
        this.traceId = traceId;
    }

    public ErrorResponse() {}

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
