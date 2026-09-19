package com.thinhbui303.observability.core.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Unified envelope response format for all API calls")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedResponse<T> {
    @Schema(description = "Status code of the response (e.g., SUCCESS, VALIDATION_ERROR, NOT_FOUND)", example = "SUCCESS")
    private String code;
    @Schema(description = "The main response data payload")
    private T data;
    @Schema(description = "Message providing more context, usually on errors")
    private String message;
    @Schema(description = "Timestamp of the response (ISO-8601)", example = "2026-09-08T10:15:30Z")
    private String timestamp;
    @Schema(description = "API path that was called", example = "/api/v1/services")
    private String path;
    @Schema(description = "List of validation errors if status is VALIDATION_ERROR")
    private List<Map<String, String>> fieldErrors;

    public UnifiedResponse(String code, T data) {
        this.code = code;
        this.data = data;
    }

    public UnifiedResponse(String code, String message, String timestamp, String path) {
        this.code = code;
        this.message = message;
        this.timestamp = timestamp;
        this.path = path;
    }

    // Getters and Setters
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public java.util.List<java.util.Map<String, String>> getFieldErrors() { return fieldErrors; }
    public void setFieldErrors(java.util.List<java.util.Map<String, String>> fieldErrors) { this.fieldErrors = fieldErrors; }
}
