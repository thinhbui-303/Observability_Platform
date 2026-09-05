package com.thinhbui303.observability.ingestion.ratelimit;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local Fallback - scope tới mức 6.1.1 trong LLD
 * KHÔNG đảm bảo global rate limit. Traffic thực tế có thể lên tới 100 * N instances
 * Đây là hành vi được chấp nhận có chủ đích (expected behavior) khi Redis gián đoạn.
 */
@Service
public class LocalBucketFallbackService {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_SECOND = 100;

    public boolean isAllowed(String serviceId) {
        long currentSecond = System.currentTimeMillis() / 1000;
        Bucket bucket = buckets.compute(serviceId, (k, b) -> {
            if (b == null || b.second() != currentSecond) {
                return new Bucket(currentSecond, new AtomicInteger(1));
            }
            b.count().incrementAndGet();
            return b;
        });

        return bucket.count().get() <= MAX_REQUESTS_PER_SECOND;
    }

    private record Bucket(long second, AtomicInteger count) {}
}
