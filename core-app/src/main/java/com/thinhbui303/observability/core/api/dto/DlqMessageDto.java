package com.thinhbui303.observability.core.api.dto;

import java.util.Map;

public record DlqMessageDto(
        int partition,
        long offset,
        String key,
        String payload,
        Map<String, String> headers,
        String errorReason,
        String firstFailedAt
) {}
