package com.thinhbui303.observability.core.api.dto;

public record AuditLogResponse(
        Long id, String username, String action, String resourceTarget,
        String ipAddress, String resultStatus, String createdAt
) {}