package com.thinhbui303.observability.common;

import java.time.Instant;
import java.util.Map;

public record CanonicalLogEvent(
        int schemaVersion,
        String eventId,
        Instant timestamp,
        String serviceName,
        String environment,
        String level,
        String message,
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
) {}
