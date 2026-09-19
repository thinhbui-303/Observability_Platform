package com.thinhbui303.observability.ingestion.service;

import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MetricsPublisherService {

    private final StringRedisTemplate redisTemplate;

    public MetricsPublisherService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publishMetrics(CanonicalLogEvent event, String serviceId) {
        String key = "metrics:service:" + serviceId;
        
        long now = System.currentTimeMillis();
        
        // Use a pipeline for performance
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
            byte[] totalField = redisTemplate.getStringSerializer().serialize("logs_total");
            byte[] errorsField = redisTemplate.getStringSerializer().serialize("errors_total");
            byte[] lastSeenField = redisTemplate.getStringSerializer().serialize("last_seen");
            
            // Increment logs_total
            connection.hashCommands().hIncrBy(rawKey, totalField, 1);
            
            // Increment errors_total if level is ERROR or FATAL
            String level = event.level();
            if (level != null && (level.equalsIgnoreCase("ERROR") || level.equalsIgnoreCase("FATAL"))) {
                connection.hashCommands().hIncrBy(rawKey, errorsField, 1);
            }
            
            // Update last_seen
            byte[] nowValue = redisTemplate.getStringSerializer().serialize(String.valueOf(now));
            connection.hashCommands().hSet(rawKey, lastSeenField, nowValue);
            
            return null;
        });
    }

    public void publishBatchMetrics(List<CanonicalLogEvent> events, String serviceId) {
        if (events == null || events.isEmpty()) return;
        
        String key = "metrics:service:" + serviceId;
        long now = System.currentTimeMillis();
        
        long totalCount = events.size();
        long errorCount = events.stream()
                .filter(e -> e.level() != null && (e.level().equalsIgnoreCase("ERROR") || e.level().equalsIgnoreCase("FATAL")))
                .count();

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
            byte[] totalField = redisTemplate.getStringSerializer().serialize("logs_total");
            byte[] errorsField = redisTemplate.getStringSerializer().serialize("errors_total");
            byte[] lastSeenField = redisTemplate.getStringSerializer().serialize("last_seen");
            
            connection.hashCommands().hIncrBy(rawKey, totalField, totalCount);
            if (errorCount > 0) {
                connection.hashCommands().hIncrBy(rawKey, errorsField, errorCount);
            }
            byte[] nowValue = redisTemplate.getStringSerializer().serialize(String.valueOf(now));
            connection.hashCommands().hSet(rawKey, lastSeenField, nowValue);
            
            return null;
        });
    }
    
    private boolean isErrorEquivalent(String level) {
        return false;
    }
}
