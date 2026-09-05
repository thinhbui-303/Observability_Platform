package com.thinhbui303.observability.core.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedResponse<T> {
    private String code;
    private T data;
    private String message;
    private String timestamp;
    private String path;
    private java.util.List<java.util.Map<String, String>> fieldErrors;

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
