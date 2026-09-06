package com.thinhbui303.observability.core.dashboard.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonData;
import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import com.thinhbui303.observability.core.dashboard.dto.DashboardMetricsPayload;
import com.thinhbui303.observability.core.dashboard.dto.ServiceHealthEntry;
import com.thinhbui303.observability.core.domain.ServiceEntity;
import com.thinhbui303.observability.core.repository.AlertRepository;
import com.thinhbui303.observability.core.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardMetricsService {

    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsService.class);
    private static final List<String> OPEN_STATUSES = List.of("TRIGGERED", "OPEN", "ACKNOWLEDGED");

    private final ElasticsearchClient esClient;
    private final AlertRepository alertRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceHealthCalculator healthCalculator;
    private final int windowSeconds;

    public DashboardMetricsService(ElasticsearchClient esClient,
                                   AlertRepository alertRepository,
                                   ServiceRepository serviceRepository,
                                   ServiceHealthCalculator healthCalculator,
                                   @Value("${app.dashboard.metrics-window-seconds:300}") int windowSeconds) {
        this.esClient = esClient;
        this.alertRepository = alertRepository;
        this.serviceRepository = serviceRepository;
        this.healthCalculator = healthCalculator;
        this.windowSeconds = windowSeconds;
    }

    public DashboardSnapshotCache.Snapshot computeNow() {
        Instant now = Instant.now();
        try {
            PerServiceStats stats = queryPerServiceStats(now);
            long totalDocs = stats.totalDocs;
            long errorDocs = stats.errorDocs;

            long openCount = alertRepository.countByStatusIn(OPEN_STATUSES);
            double logsPerSecond = totalDocs / (double) windowSeconds;
            double errorRatePercent = totalDocs == 0 ? 0.0 : errorDocs * 100.0 / totalDocs;

            DashboardMetricsPayload metrics = new DashboardMetricsPayload(
                    logsPerSecond, errorRatePercent, openCount, windowSeconds, now);
            return new DashboardSnapshotCache.Snapshot(metrics, stats.entries);
        } catch (IOException e) {
            log.error("Dashboard metrics computation failed; keeping last snapshot", e);
            throw new IllegalStateException("dashboard metrics ES query failed", e);
        }
    }

    private PerServiceStats queryPerServiceStats(Instant now) throws IOException {
        Instant since = now.minusSeconds(windowSeconds);
        SearchRequest request = new SearchRequest.Builder()
                .index("logs-*")
                .size(0)
                .query(q -> q.bool(b -> b.filter(
                        f -> f.range(r -> r.field("timestamp").gte(JsonData.of(since.toString())).lt(JsonData.of(now.toString()))))))
                .aggregations("by_service", a -> a.terms(t -> t.field("serviceName").size(500))
                        .aggregations("max_ts", m -> m.max(mm -> mm.field("timestamp")))
                        .aggregations("err_count", e -> e.filter(
                                f -> f.term(t -> t.field("level").value("ERROR")))))
                .build();
        var response = esClient.search(request, Object.class);
        StringTermsAggregate byService = response.aggregations().get("by_service").sterms();

        java.util.Map<String, ServiceStats> perService = new java.util.HashMap<>();
        for (StringTermsBucket bucket : byService.buckets().array()) {
            String service = bucket.key().stringValue();
            long total = bucket.docCount();
            long errs = bucket.aggregations().get("err_count").filter().docCount();
            Instant lastSeen = Instant.ofEpochMilli((long) bucket.aggregations().get("max_ts").max().value());
            perService.put(service, new ServiceStats(total, errs, lastSeen));
        }

        List<ServiceHealthEntry> entries = new ArrayList<>();
        long totalDocs = 0;
        long errorDocs = 0;
        for (ServiceEntity service : serviceRepository.findAll()) {
            ServiceStats stats = perService.get(service.getId());
            if (stats == null) {
                entries.add(new ServiceHealthEntry(service.getId(), "UNAVAILABLE", null, 0.0));
            } else {
                double errorRate = stats.total == 0 ? 0.0 : stats.errorCount / (double) stats.total;
                entries.add(new ServiceHealthEntry(
                        service.getId(),
                        healthCalculator.evaluate(stats.lastSeen, errorRate, now),
                        stats.lastSeen,
                        errorRate * 100.0));
                totalDocs += stats.total;
                errorDocs += stats.errorCount;
            }
        }
        return new PerServiceStats(entries, totalDocs, errorDocs);
    }

    private record ServiceStats(long total, long errorCount, Instant lastSeen) {}

    private record PerServiceStats(List<ServiceHealthEntry> entries, long totalDocs, long errorDocs) {}
}
