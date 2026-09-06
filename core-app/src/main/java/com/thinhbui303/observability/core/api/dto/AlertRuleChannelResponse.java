package com.thinhbui303.observability.core.api.dto;

import com.thinhbui303.observability.common.ChannelType;

public record AlertRuleChannelResponse(
        Long id, ChannelType channelType, String target, boolean enabled
) {}
