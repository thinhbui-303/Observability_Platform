package com.thinhbui303.observability.core.dashboard.cache;

import com.thinhbui303.observability.core.dashboard.dto.DashboardMetricsPayload;
import com.thinhbui303.observability.core.dashboard.dto.ServiceHealthEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory snapshot of the last computed metrics + service-health tick.
 * SRS line 674: on reconnect a client should see current data immediately,
 * so subscribe-time replay reads this cache instead of waiting for the next tick.
 */
@Component
public class DashboardSnapshotCache {

    private final AtomicReference<Snapshot> latest = new AtomicReference<>();

    public void update(DashboardMetricsPayload metrics, List<ServiceHealthEntry> serviceHealth) {
        latest.set(new Snapshot(metrics, List.copyOf(serviceHealth)));
    }

    /** @return current snapshot, or null before the first @Scheduled tick. */
    public Snapshot currentSnapshot() {
        return latest.get();
    }

    public record Snapshot(DashboardMetricsPayload metrics, List<ServiceHealthEntry> serviceHealth) {}
}
