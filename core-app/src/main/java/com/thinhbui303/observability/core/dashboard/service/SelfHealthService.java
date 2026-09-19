package com.thinhbui303.observability.core.dashboard.service;

import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import com.thinhbui303.observability.core.dashboard.dto.SelfHealthPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SelfHealthService {

    private static final Logger log = LoggerFactory.getLogger(SelfHealthService.class);

    private final MeterRegistry meterRegistry;
    private final KafkaLagService kafkaLagService;

    private static final List<String> CORE_CONSUMER_GROUPS = List.of(
            "indexer-group",
            "retry-worker-group",
            "alert-persistence-group",
            "notification-dispatch-group",
            "dashboard-broadcast-group"
    );

    public SelfHealthService(MeterRegistry meterRegistry, KafkaLagService kafkaLagService) {
        this.meterRegistry = meterRegistry;
        this.kafkaLagService = kafkaLagService;
    }

    public DashboardSnapshotCache.SelfHealthSnapshot computeNow() {
        try {
            double memUsed = getGaugeValue("jvm.memory.used");
            double memMax = getGaugeValue("jvm.memory.max");
            double memUsedPercent = (memMax > 0) ? (memUsed / memMax) * 100.0 : 0.0;
            double memMaxMb = memMax / (1024.0 * 1024.0);

            double cpuUsage = getGaugeValue("process.cpu.usage") * 100.0;
            if (Double.isNaN(cpuUsage)) cpuUsage = 0.0;

            double p99Latency = 0.0;
            long totalRequests = 0;
            long errorRequests = 0;

            var timers = meterRegistry.find("http.server.requests").timers();
            if (timers != null && !timers.isEmpty()) {
                for (Timer t : timers) {
                    totalRequests += t.count();
                    String status = t.getId().getTag("status");
                    if (status != null && status.startsWith("5")) {
                        errorRequests += t.count();
                    }
                    var snapshot = t.takeSnapshot();
                    if (snapshot != null) {
                        var percentiles = snapshot.percentileValues();
                        if (percentiles != null) {
                            for (var p : percentiles) {
                                if (Math.abs(p.percentile() - 0.99) < 0.001) {
                                    double valMs = p.value(TimeUnit.MILLISECONDS);
                                    if (valMs > p99Latency) {
                                        p99Latency = valMs;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            double errorRatePercent = (totalRequests > 0) ? ((double) errorRequests / totalRequests) * 100.0 : 0.0;

            List<SelfHealthPayload.ConsumerLag> consumerLags = new ArrayList<>();
            for (String groupId : CORE_CONSUMER_GROUPS) {
                long lag = kafkaLagService.getConsumerGroupLag(groupId);
                consumerLags.add(new SelfHealthPayload.ConsumerLag(groupId, lag));
            }

            SelfHealthPayload payload = new SelfHealthPayload(
                    Instant.now(),
                    memUsedPercent,
                    memMaxMb,
                    cpuUsage,
                    p99Latency,
                    errorRatePercent,
                    consumerLags
            );

            return new DashboardSnapshotCache.SelfHealthSnapshot(payload);
        } catch (Exception e) {
            log.error("Failed to compute self-health metrics", e);
            throw new IllegalStateException("Self health metrics computation failed", e);
        }
    }

    private double getGaugeValue(String metricName) {
        var gauge = meterRegistry.find(metricName).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }
}
