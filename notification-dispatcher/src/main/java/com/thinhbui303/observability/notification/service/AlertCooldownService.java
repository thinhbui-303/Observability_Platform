package com.thinhbui303.observability.notification.service;

import com.thinhbui303.observability.common.CanonicalAlertEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class AlertCooldownService {

    private static final Logger log = LoggerFactory.getLogger(AlertCooldownService.class);

    public static final long COOLDOWN_TTL_SECONDS = 300;

    private static final DefaultRedisScript<Long> COOLDOWN_SCRIPT = new DefaultRedisScript<>(
            // KEYS[1] = cooldown key, KEYS[2] = counter key, ARGV[1] = TTL seconds
            "if redis.call('SET', KEYS[1], '1', 'EX', ARGV[1], 'NX') then " +
            "  redis.call('SET', KEYS[2], '1', 'EX', ARGV[1]) " +
            "  return 1 " +
            "end " +
            "local count = redis.call('INCR', KEYS[2]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[1]) " +
            "return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public AlertCooldownService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * First alert in the 300s window -> true (send). Later alerts -> false (suppressed;
     * atomic counter incremented). On Redis failure the DataAccessException bubbles so the
     * caller can enter degraded mode (Ruling C).
     */
    public boolean acquire(CanonicalAlertEvent event) {
        Long result = redisTemplate.execute(COOLDOWN_SCRIPT,
                List.of(cooldownKey(event), counterKey(event)),
                String.valueOf(COOLDOWN_TTL_SECONDS));
        return Long.valueOf(1L).equals(result);
    }

    /** Release the cooldown lock after a FAILED send so the next alert can retry. */
    public void release(CanonicalAlertEvent event) {
        redisTemplate.delete(cooldownKey(event));
    }

    private String cooldownKey(CanonicalAlertEvent event) {
        return "alert:cooldown:" + event.serviceId() + ":" + event.environment() + ":" + event.ruleId();
    }

    private String counterKey(CanonicalAlertEvent event) {
        return "alert:counter:" + event.serviceId() + ":" + event.environment() + ":" + event.ruleId();
    }
}
