package com.thinhbui303.observability.core.api.dto;

import java.util.List;

public record AlertRuleResponse(
        Long id, String ruleName, String serviceId, String environment,
        String conditionType, Integer thresholdValue, Integer windowSeconds,
        String conditionValue, String severity, boolean isEnabled, String createdAt,
        List<AlertRuleChannelResponse> notificationChannels
) {}
