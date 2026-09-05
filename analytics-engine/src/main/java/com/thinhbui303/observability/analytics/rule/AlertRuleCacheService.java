package com.thinhbui303.observability.analytics.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AlertRuleCacheService {
    private static final Logger log = LoggerFactory.getLogger(AlertRuleCacheService.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    // Key: serviceId||environment
    private volatile Map<String, List<AlertRuleConfig>> ruleCache = new ConcurrentHashMap<>();

    public AlertRuleCacheService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        loadRules();
    }

    @Scheduled(fixedRateString = "${analytics.rule.cache.refresh-rate:30000}")
    public void loadRules() {
        log.info("Refreshing AlertRuleCache...");
        String sql = "SELECT r.id AS rule_id, r.service_id, r.environment, r.threshold_value, " +
                     "r.condition_type, r.condition_value, r.severity, c.channel_type, c.target " +
                     "FROM alert_rules r " +
                     "LEFT JOIN alert_rule_channels c ON c.alert_rule_id = r.id " +
                     "WHERE r.is_enabled = true AND r.condition_type IN ('ERROR_SPIKE', 'PATTERN_MATCH')";

        Map<String, List<AlertRuleConfig>> newCache = new ConcurrentHashMap<>();

        jdbcTemplate.query(sql, (rs) -> {
            Long ruleId = rs.getLong("rule_id");
            String serviceId = rs.getString("service_id");
            String environment = rs.getString("environment");
            String conditionType = rs.getString("condition_type");
            String conditionValue = rs.getString("condition_value");
            int thresholdValue = rs.getInt("threshold_value");
            String severity = rs.getString("severity");
            String channelType = rs.getString("channel_type");
            String target = rs.getString("target");

            String cacheKey = serviceId + "||" + environment;

            List<AlertRuleConfig> rulesForKey = newCache.computeIfAbsent(cacheKey, k -> new ArrayList<>());

            // Check if this rule already exists in the list (since JOIN might return multiple rows for multiple channels)
            AlertRuleConfig existingRule = rulesForKey.stream()
                    .filter(r -> r.ruleId().equals(ruleId))
                    .findFirst()
                    .orElse(null);

            if (existingRule == null) {
                List<String> channels = new ArrayList<>();
                if (channelType != null && target != null) {
                    channels.add(channelType + ":" + target);
                }
                AlertRuleConfig newRule = new AlertRuleConfig(ruleId, serviceId, environment, conditionType, conditionValue, thresholdValue, severity, channels);
                rulesForKey.add(newRule);
            } else {
                if (channelType != null && target != null) {
                    existingRule.notificationChannels().add(channelType + ":" + target);
                }
            }
        });

        this.ruleCache = newCache;
        log.info("AlertRuleCache refreshed with {} unique service-environment combinations.", newCache.size());
    }

    public List<AlertRuleConfig> getRules(String serviceId, String environment) {
        String key = serviceId + "||" + environment;
        return ruleCache.getOrDefault(key, List.of());
    }
}
