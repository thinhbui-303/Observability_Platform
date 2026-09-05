package com.thinhbui303.observability.analytics.rule;

import java.util.List;

public record AlertRuleConfig(
        Long ruleId,
        String serviceId,
        String environment,
        String conditionType,
        String conditionValue,
        int thresholdValue,
        String severity,
        List<String> notificationChannels
) {
}
