package com.thinhbui303.observability.ingestion.ratelimit;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TokenBucketRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitLuaScript;
    private final LocalBucketFallbackService fallbackService;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                  RedisScript<Long> rateLimitLuaScript,
                                  LocalBucketFallbackService fallbackService) {
        this.redisTemplate = redisTemplate;
        this.rateLimitLuaScript = rateLimitLuaScript;
        this.fallbackService = fallbackService;
    }

    public void checkAllowed(String serviceId, long capacity, long refillRatePerSec) {
        boolean allowed;
        try {
            List<String> keys = List.of("ratelimit:service:" + serviceId);
            long nowMs = System.currentTimeMillis();
            Long result = redisTemplate.execute(
                    rateLimitLuaScript, keys,
                    String.valueOf(capacity), String.valueOf(refillRatePerSec), String.valueOf(nowMs)
            );
            allowed = (result != null && result == 1L);
        } catch (RedisConnectionFailureException ex) {
            allowed = fallbackService.isAllowed(serviceId);
        } catch (Exception ex) {
            allowed = fallbackService.isAllowed(serviceId);
        }

        if (!allowed) {
            throw new RateLimitExceededException("Rate limit exceeded for service: " + serviceId);
        }
    }
}
