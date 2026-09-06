package com.thinhbui303.observability.core.dashboard.dto;

import java.time.Instant;

public record ServiceHealthEntry(
        String serviceId,
        String status,
        Instant lastSeen,
        double errorRatePercent
) {}
