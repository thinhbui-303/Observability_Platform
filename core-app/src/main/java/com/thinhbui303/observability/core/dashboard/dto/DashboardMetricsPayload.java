package com.thinhbui303.observability.core.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record DashboardMetricsPayload(
        Instant timestamp,
        List<ServiceMetrics> services,
        KafkaMetrics kafka
) {
    public record ServiceMetrics(
            String serviceId,
            String serviceName,
            String status,
            double logsPerSecond,
            double errorRate,
            Instant lastSeen
    ) {}

    public record KafkaMetrics(
            long indexerLag,
            long analyticsLag
    ) {}
}
