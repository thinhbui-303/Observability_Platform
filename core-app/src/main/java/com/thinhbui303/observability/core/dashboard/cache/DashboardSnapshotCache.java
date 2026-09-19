package com.thinhbui303.observability.core.dashboard.cache;

import com.thinhbui303.observability.core.dashboard.dto.DashboardMetricsPayload;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory snapshot of the last computed metrics + service-health tick.
 * SRS line 674: on reconnect a client should see current data immediately,
 * so subscribe-time replay reads this cache instead of waiting for the next tick.
 */
@Component
public class DashboardSnapshotCache {

    private final AtomicReference<Snapshot> latest = new AtomicReference<>();
    private final AtomicReference<SelfHealthSnapshot> latestSelfHealth = new AtomicReference<>();

    public void update(DashboardMetricsPayload metrics) {
        latest.set(new Snapshot(metrics));
    }

    public void updateSelfHealth(com.thinhbui303.observability.core.dashboard.dto.SelfHealthPayload payload) {
        latestSelfHealth.set(new SelfHealthSnapshot(payload));
    }

    /** @return current snapshot, or null before the first @Scheduled tick. */
    public Snapshot currentSnapshot() {
        return latest.get();
    }

    public SelfHealthSnapshot currentSelfHealthSnapshot() {
        return latestSelfHealth.get();
    }

    public record Snapshot(DashboardMetricsPayload metrics) {}
    public record SelfHealthSnapshot(com.thinhbui303.observability.core.dashboard.dto.SelfHealthPayload payload) {}
}
