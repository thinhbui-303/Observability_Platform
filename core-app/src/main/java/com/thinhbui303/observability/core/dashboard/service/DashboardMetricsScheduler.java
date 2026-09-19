package com.thinhbui303.observability.core.dashboard.service;

import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DashboardMetricsScheduler {

    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsScheduler.class);
    private static final String TOPIC_METRICS = "/topic/dashboard/metrics";
    private static final String TOPIC_HEALTH = "/topic/dashboard/service-health";
    private static final String TOPIC_SELF_HEALTH = "/topic/dashboard/self-health";

    private final DashboardMetricsService metricsService;
    private final SelfHealthService selfHealthService;
    private final DashboardSnapshotCache snapshotCache;
    private final SimpMessagingTemplate messagingTemplate;

    public DashboardMetricsScheduler(DashboardMetricsService metricsService,
                                     SelfHealthService selfHealthService,
                                     DashboardSnapshotCache snapshotCache,
                                     SimpMessagingTemplate messagingTemplate) {
        this.metricsService = metricsService;
        this.selfHealthService = selfHealthService;
        this.snapshotCache = snapshotCache;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRateString = "${app.dashboard.metrics-tick-ms:5000}")
    public void broadcastDashboardTick() {
        try {
            DashboardSnapshotCache.Snapshot snapshot = metricsService.computeNow();
            snapshotCache.update(snapshot.metrics());
            messagingTemplate.convertAndSend(TOPIC_METRICS, snapshot.metrics());
        } catch (RuntimeException e) {
            log.error("Dashboard tick failed (broadcast skipped this round)", e);
        }

        try {
            DashboardSnapshotCache.SelfHealthSnapshot selfHealthSnapshot = selfHealthService.computeNow();
            snapshotCache.updateSelfHealth(selfHealthSnapshot.payload());
            messagingTemplate.convertAndSend(TOPIC_SELF_HEALTH, selfHealthSnapshot.payload());
        } catch (RuntimeException e) {
            log.error("Self-health tick failed (broadcast skipped this round)", e);
        }
    }
}
