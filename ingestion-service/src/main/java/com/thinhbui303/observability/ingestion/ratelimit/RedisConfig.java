package com.thinhbui303.observability.ingestion.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> rateLimitLuaScript() {
        String script = """
                local key       = KEYS[1]
                local capacity  = tonumber(ARGV[1])
                local refillRate = tonumber(ARGV[2])
                local now_ms    = tonumber(ARGV[3])
                
                local last_updated = tonumber(redis.call('HGET', key, 'last_updated') or now_ms)
                local tokens       = tonumber(redis.call('HGET', key, 'tokens') or capacity)
                
                local delta_ms = math.max(0, now_ms - last_updated)
                local refilled = (delta_ms / 1000.0) * refillRate
                tokens = math.min(capacity, tokens + refilled)
                
                if tokens >= 1 then
                    tokens = tokens - 1
                    redis.call('HSET', key, 'tokens', tokens, 'last_updated', now_ms)
                    redis.call('EXPIRE', key, 60)
                    return 1
                else
                    redis.call('HSET', key, 'tokens', tokens, 'last_updated', now_ms)
                    redis.call('EXPIRE', key, 60)
                    return 0
                end
                """;
        return new DefaultRedisScript<>(script, Long.class);
    }
}
