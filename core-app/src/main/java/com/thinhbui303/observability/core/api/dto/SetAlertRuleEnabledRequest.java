package com.thinhbui303.observability.core.api.dto;

import jakarta.validation.constraints.NotNull;

public record SetAlertRuleEnabledRequest(
        @NotNull Boolean enabled
) {}
