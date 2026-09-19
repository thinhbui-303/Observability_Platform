package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.common.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Request payload for creating a new alert rule")
public record CreateAlertRuleRequest(
        @NotBlank @Size(max = 100) @Schema(description = "Name of the alert rule", example = "High Error Rate") String ruleName,
        @NotBlank @Schema(description = "The target service to monitor", example = "payment-service") String serviceId,
        @NotBlank @Size(max = 20) @Schema(description = "Environment filter", example = "production") String environment,
        @NotNull @Schema(description = "Condition type", example = "ERROR_COUNT_THRESHOLD") ConditionType conditionType,
        @Schema(description = "Threshold value", example = "100") Integer thresholdValue,
        @Schema(description = "Time window in seconds to evaluate the condition", example = "300") Integer windowSeconds,
        @Schema(description = "Value to compare against or pattern to match", example = "50") String conditionValue,
        @NotNull @Schema(description = "Severity level when triggered", example = "CRITICAL") Severity severity,
        @Schema(description = "Whether the alert rule is active", example = "true") Boolean isEnabled,
        @Schema(description = "Channels to notify when alert triggers") List<ChannelRequest> notificationChannels
) {}
