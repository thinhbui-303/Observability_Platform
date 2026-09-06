package com.thinhbui303.observability.core.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String teamOwner,
        @NotBlank @Size(max = 20) String environment
) {}