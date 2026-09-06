package com.thinhbui303.observability.core.api.dto;

public record AlertResponse(
        String id, Long ruleId, String serviceId, String environment,
        String windowStart, String severity, String status,
        String triggeredAt, String acknowledgedAt, String resolvedAt,
        int occurrenceCount
) {}