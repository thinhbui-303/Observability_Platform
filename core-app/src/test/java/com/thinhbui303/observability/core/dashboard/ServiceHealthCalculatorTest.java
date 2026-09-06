package com.thinhbui303.observability.core.dashboard;

import com.thinhbui303.observability.core.dashboard.service.ServiceHealthCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceHealthCalculatorTest {

    // Pure function: construct directly with the production thresholds (no Spring needed).
    private final ServiceHealthCalculator calculator = new ServiceHealthCalculator(120_000L, 30_000L, 0.05);

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Test
    void testServiceHealthCalculator_UNAVAILABLE_WhenLastSeenAtOrBeyond2Minutes() {
        // exactly 2 minutes -> UNAVAILABLE (boundary, >=)
        Instant lastSeen = NOW.minusSeconds(120);
        assertThat(calculator.evaluate(lastSeen, 0.0, NOW)).isEqualTo("UNAVAILABLE");
        // just under 2 min (119s), 0 error -> DEGRADED (below the 120s boundary)
        assertThat(calculator.evaluate(NOW.minusSeconds(119), 0.0, NOW)).isEqualTo("DEGRADED");
        // beyond 2 minutes -> still UNAVAILABLE first, regardless of error rate
        assertThat(calculator.evaluate(NOW.minusSeconds(600), 0.99, NOW)).isEqualTo("UNAVAILABLE");
        // no logs ever -> UNAVAILABLE
        assertThat(calculator.evaluate(null, 0.0, NOW)).isEqualTo("UNAVAILABLE");
    }

    @Test
    void testServiceHealthCalculator_DEGRADED_AtExactly30Seconds() {
        // 29s ago, 0 error -> HEALTHY (below the 30s boundary)
        assertThat(calculator.evaluate(NOW.minusSeconds(29), 0.0, NOW)).isEqualTo("HEALTHY");
        // exactly 30s ago, 0 error -> DEGRADED (boundary, >=)
        assertThat(calculator.evaluate(NOW.minusSeconds(30), 0.0, NOW)).isEqualTo("DEGRADED");
        // 31s ago -> DEGRADED
        assertThat(calculator.evaluate(NOW.minusSeconds(31), 0.0, NOW)).isEqualTo("DEGRADED");
    }

    @Test
    void testServiceHealthCalculator_HEALTHY_AtExactly5PercentBoundary() {
        // 4.9% error, fresh -> HEALTHY
        assertThat(calculator.evaluate(NOW.minusSeconds(10), 0.049, NOW)).isEqualTo("HEALTHY");
        // exactly 5% error -> DEGRADED (boundary, >=)
        assertThat(calculator.evaluate(NOW.minusSeconds(10), 0.05, NOW)).isEqualTo("DEGRADED");
        // 5.1% error -> DEGRADED
        assertThat(calculator.evaluate(NOW.minusSeconds(10), 0.051, NOW)).isEqualTo("DEGRADED");
    }

    @Test
    void testServiceHealthCalculator_ZeroErrorAndFresh_IsHealthy() {
        assertThat(calculator.evaluate(NOW.minusSeconds(5), 0.0, NOW)).isEqualTo("HEALTHY");
    }
}
