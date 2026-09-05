package com.thinhbui303.observability.notification.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChannelConfigService {

    private final JdbcTemplate jdbcTemplate;

    public ChannelConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ChannelConfig(String type, String target) {}

    public List<ChannelConfig> channelsFor(Long ruleId) {
        return jdbcTemplate.query(
                "SELECT channel_type, target FROM alert_rule_channels " +
                "WHERE alert_rule_id = ? AND enabled = TRUE AND channel_type IN ('SLACK', 'TELEGRAM', 'WEBHOOK')",
                (rs, rowNum) -> new ChannelConfig(rs.getString("channel_type"), rs.getString("target")),
                ruleId);
    }
}
