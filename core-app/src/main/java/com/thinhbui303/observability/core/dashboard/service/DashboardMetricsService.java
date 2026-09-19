package com.thinhbui303.observability.core.dashboard.service;

import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import com.thinhbui303.observability.core.dashboard.dto.DashboardMetricsPayload;
import com.thinhbui303.observability.core.domain.ServiceEntity;
import com.thinhbui303.observability.core.repository.AlertRepository;
import com.thinhbui303.observability.core.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardMetricsService {

    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsService.class);

    private final StringRedisTemplate redisTemplate;
    private final AlertRepository alertRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceHealthCalculator healthCalculator;
    private final KafkaLagService kafkaLagService;
    private final int windowSeconds;

    public DashboardMetricsService(StringRedisTemplate redisTemplate,
                                   AlertRepository alertRepository,
                                   ServiceRepository serviceRepository,
                                   ServiceHealthCalculator healthCalculator,
                                   KafkaLagService kafkaLagService,
                                   @Value("${app.dashboard.metrics-window-seconds:300}") int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.alertRepository = alertRepository;
        this.serviceRepository = serviceRepository;
        this.healthCalculator = healthCalculator;
        this.kafkaLagService = kafkaLagService;
        this.windowSeconds = windowSeconds;
    }

    public DashboardSnapshotCache.Snapshot computeNow() {
        Instant now = Instant.now();
        try {
            List<DashboardMetricsPayload.ServiceMetrics> serviceMetricsList = new ArrayList<>();
            List<ServiceEntity> services = serviceRepository.findAll();
            
            for (ServiceEntity service : services) {
                String key = "metrics:service:" + service.getId();
                Object totalObj = redisTemplate.opsForHash().get(key, "logs_total");
                Object errorObj = redisTemplate.opsForHash().get(key, "errors_total");
                Object lastSeenObj = redisTemplate.opsForHash().get(key, "last_seen");

                long total = totalObj != null ? Long.parseLong(totalObj.toString()) : 0;
                long errors = errorObj != null ? Long.parseLong(errorObj.toString()) : 0;
                Instant lastSeen = lastSeenObj != null ? Instant.ofEpochMilli(Long.parseLong(lastSeenObj.toString())) : null;

                double logsPerSecond = total / (double) windowSeconds;
                double errorRate = total == 0 ? 0.0 : (double) errors / total;
                String status = healthCalculator.evaluate(lastSeen, errorRate, now);

                serviceMetricsList.add(new DashboardMetricsPayload.ServiceMetrics(
                        service.getId(),
                        service.getName(),
                        status,
                        logsPerSecond,
                        errorRate,
                        lastSeen
                ));
            }

            long indexerLag = kafkaLagService.getConsumerGroupLag("indexer-group");
            long analyticsLag = kafkaLagService.getConsumerGroupLag("analytics-group");
            DashboardMetricsPayload.KafkaMetrics kafkaMetrics = new DashboardMetricsPayload.KafkaMetrics(indexerLag, analyticsLag);

            DashboardMetricsPayload metrics = new DashboardMetricsPayload(
                    now,
                    serviceMetricsList,
                    kafkaMetrics
            );
            
            return new DashboardSnapshotCache.Snapshot(metrics);
        } catch (Exception e) {
            log.error("Dashboard metrics computation failed; keeping last snapshot", e);
            throw new IllegalStateException("dashboard metrics redis query failed", e);
        }
    }
}
