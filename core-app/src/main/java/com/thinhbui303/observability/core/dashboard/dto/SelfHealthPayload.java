package com.thinhbui303.observability.core.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record SelfHealthPayload(
        Instant timestamp,
        double jvmMemoryUsedPercent,
        double jvmMemoryMaxMb,
        double cpuUsagePercent,
        double apiP99LatencyMs,
        double apiErrorRatePercent,
        List<ConsumerLag> consumerLag
) {
    public record ConsumerLag(
            String groupId,
            long lag
    ) {}
}
