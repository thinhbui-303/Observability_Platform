package com.thinhbui303.observability.core.dashboard.dto;

import java.time.Instant;

public record DashboardMetricsPayload(
        double logsPerSecond,
        double errorRatePercent,
        long openAlertCount,
        int windowSeconds,
        Instant computedAt
) {}
