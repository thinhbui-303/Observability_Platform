package com.thinhbui303.observability.ingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

@Schema(description = "Request payload for ingesting a single log event")
public record LogIngestionRequest(
        @Nullable @Schema(description = "Unique event identifier for idempotency", example = "evt-12345-abcde") 
        String eventId,
        @NotNull @Schema(description = "Timestamp of the log event (ISO-8601)", example = "2026-09-08T10:15:30Z") 
        Instant timestamp,
        @NotBlank @Schema(description = "Log level (e.g., INFO, ERROR, DEBUG)", example = "ERROR") 
        String level,
        @NotBlank @Schema(description = "The main log message", example = "Database connection timed out after 30s") 
        String message,
        @Schema(description = "Trace ID for distributed tracing correlation", example = "4bf92f3577b34da6a3ce929d0e0e4736") 
        String traceId,
        @Schema(description = "Span ID for distributed tracing correlation", example = "00f067aa0ba902b7") 
        String spanId,
        @Schema(description = "Host name or identifier where the log originated") 
        String host,
        @Schema(description = "Instance ID for clustered environments") 
        String instanceId,
        @Schema(description = "Logger name, usually the class path") 
        String logger,
        @Schema(description = "HTTP method if applicable", example = "GET") 
        String httpMethod,
        @Schema(description = "API endpoint path", example = "/api/v1/users") 
        String endpoint,
        @Schema(description = "HTTP status code", example = "500") 
        Integer statusCode,
        @Schema(description = "Execution duration in milliseconds", example = "300") 
        Long durationMs,
        @Schema(description = "Additional structured metadata attributes") 
        Map<String, Object> metadata
) {
}
