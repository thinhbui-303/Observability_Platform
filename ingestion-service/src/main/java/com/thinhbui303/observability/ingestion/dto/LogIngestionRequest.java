package com.thinhbui303.observability.ingestion.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

public record LogIngestionRequest(
        @Nullable String eventId,
        @NotNull Instant timestamp,
        @NotBlank String level,
        @NotBlank String message,
        String traceId,
        String spanId,
        String host,
        String instanceId,
        String logger,
        String httpMethod,
        String endpoint,
        Integer statusCode,
        Long durationMs,
        Map<String, Object> metadata
) {
}
