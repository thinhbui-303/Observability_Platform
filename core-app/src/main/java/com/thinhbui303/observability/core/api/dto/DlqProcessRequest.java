package com.thinhbui303.observability.core.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record DlqProcessRequest(
        @NotBlank(message = "Action must be RETRY or DISCARD")
        String action,
        
        @Min(value = 1, message = "Limit must be at least 1")
        @Max(value = 100, message = "Limit cannot exceed 100")
        int limit
) {}
