package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ChannelType;
import jakarta.validation.constraints.NotNull;

public record ChannelRequest(
        @NotNull ChannelType channelType,
        String target,
        Boolean enabled
) {}
