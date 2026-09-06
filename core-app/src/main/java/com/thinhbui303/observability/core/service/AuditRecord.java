package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.core.domain.AuditLogEntity;

public record AuditRecord(String username, String action, String resourceTarget,
                          String ipAddress, String resultStatus) {

    public static AuditRecord of(String username, String action, String resourceTarget,
                                 String ipAddress, String resultStatus) {
        return new AuditRecord(username, action, resourceTarget, ipAddress, resultStatus);
    }

    public AuditLogEntity toEntity() {
        AuditLogEntity e = new AuditLogEntity();
        e.setUsername(username);
        e.setAction(action);
        e.setResourceTarget(resourceTarget);
        e.setIpAddress(ipAddress);
        e.setResultStatus(resultStatus);
        return e;
    }
}