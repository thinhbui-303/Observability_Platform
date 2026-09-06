package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ServiceStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateServiceStatusRequest(
        @NotNull ServiceStatus status
) {}