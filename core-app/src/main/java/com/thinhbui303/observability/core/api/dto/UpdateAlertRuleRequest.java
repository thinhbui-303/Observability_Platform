package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.common.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateAlertRuleRequest(
        @NotBlank @Size(max = 100) String ruleName,
        @NotBlank String serviceId,
        @NotBlank @Size(max = 20) String environment,
        @NotNull ConditionType conditionType,
        Integer thresholdValue,
        Integer windowSeconds,
        String conditionValue,
        @NotNull Severity severity,
        Boolean isEnabled,
        List<ChannelRequest> notificationChannels
) {}
