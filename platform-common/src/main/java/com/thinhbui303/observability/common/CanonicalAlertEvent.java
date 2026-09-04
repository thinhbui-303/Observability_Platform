package com.thinhbui303.observability.common;

import java.time.Instant;
import java.util.List;

public record CanonicalAlertEvent(
        String alertId,
        Long ruleId,
        String serviceId,
        String environment,
        String severity,
        String status,
        String condition,
        Integer windowSeconds,
        Instant windowStart,
        Integer occurrenceCount,
        Instant triggeredAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        List<String> notificationChannels
) {}
