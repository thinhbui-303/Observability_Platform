package com.thinhbui303.observability.core.dashboard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * FR-DASH-03 / SRS 4.9.1 — Service Health evaluation, deterministic and in
 * strict order (this order is locked by decision 0.3; do not reorder):
 *   1. lastSeen >= 2 minutes                -> UNAVAILABLE  (evaluated FIRST)
 *   2. else lastSeen >= 30s OR errRate >= 5%-> DEGRADED
 *   3. else                                 -> HEALTHY
 * Pure function: no ES, no WS, no DB — easy to unit test.
 */
@Component
public class ServiceHealthCalculator {

    private final long unavailableAfterMs;
    private final long degradedAfterMs;
    private final double errorRateThreshold;

    public ServiceHealthCalculator(
            @Value("${app.dashboard.health-unavailable-after-ms:120000}") long unavailableAfterMs,
            @Value("${app.dashboard.health-degraded-after-ms:30000}") long degradedAfterMs,
            @Value("${app.dashboard.health-error-rate-threshold:0.05}") double errorRateThreshold) {
        this.unavailableAfterMs = unavailableAfterMs;
        this.degradedAfterMs = degradedAfterMs;
        this.errorRateThreshold = errorRateThreshold;
    }

    public String evaluate(Instant lastSeen, double errorRate, Instant now) {
        if (lastSeen == null) {
            return "UNAVAILABLE";
        }
        long ageMs = Duration.between(lastSeen, now).toMillis();
        if (ageMs >= unavailableAfterMs) {
            return "UNAVAILABLE";
        }
        if (ageMs >= degradedAfterMs || errorRate >= errorRateThreshold) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }
}
