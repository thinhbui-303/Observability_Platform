package com.thinhbui303.observability.core.dashboard.dto;

import java.time.Instant;

public record DashboardAlertPayload(
        String alertId,
        Long ruleId,
        String serviceId,
        String severity,
        String description,
        Instant triggeredAt
) {}
