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

    private final DashboardMetricsService metricsService;
    private final DashboardSnapshotCache snapshotCache;
    private final SimpMessagingTemplate messagingTemplate;

    public DashboardMetricsScheduler(DashboardMetricsService metricsService,
                                     DashboardSnapshotCache snapshotCache,
                                     SimpMessagingTemplate messagingTemplate) {
        this.metricsService = metricsService;
        this.snapshotCache = snapshotCache;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRateString = "${app.dashboard.metrics-tick-ms:5000}")
    public void broadcastDashboardTick() {
        try {
            DashboardSnapshotCache.Snapshot snapshot = metricsService.computeNow();
            snapshotCache.update(snapshot.metrics(), snapshot.serviceHealth());
            messagingTemplate.convertAndSend(TOPIC_METRICS, snapshot.metrics());
            messagingTemplate.convertAndSend(TOPIC_HEALTH, snapshot.serviceHealth());
        } catch (RuntimeException e) {
            log.error("Dashboard tick failed (broadcast skipped this round)", e);
        }
    }
}
