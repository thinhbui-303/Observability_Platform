package com.thinhbui303.observability.common;

import java.time.Instant;
import java.util.List;

public class ValidationErrorResponse extends ErrorResponse {
    private List<FieldError> fieldErrors;

    public ValidationErrorResponse(String code, String message, Instant timestamp, String path, String traceId, List<FieldError> fieldErrors) {
        super(code, message, timestamp, path, traceId);
        this.fieldErrors = fieldErrors;
    }

    public ValidationErrorResponse() {}

    public List<FieldError> getFieldErrors() { return fieldErrors; }
    public void setFieldErrors(List<FieldError> fieldErrors) { this.fieldErrors = fieldErrors; }

    public static class FieldError {
        private String field;
        private String message;

        public FieldError(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public FieldError() {}

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
