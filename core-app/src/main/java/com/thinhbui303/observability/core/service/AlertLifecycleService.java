package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.common.AlertStatus;
import com.thinhbui303.observability.core.api.dto.AlertResponse;
import com.thinhbui303.observability.core.api.exception.ConflictException;
import com.thinhbui303.observability.core.api.exception.NotFoundException;
import com.thinhbui303.observability.core.domain.AlertEntity;
import com.thinhbui303.observability.core.repository.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AlertLifecycleService {

    private final AlertRepository alertRepository;
    private final AuditLogService auditLogService;

    public AlertLifecycleService(AlertRepository alertRepository, AuditLogService auditLogService) {
        this.alertRepository = alertRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> list(String serviceId, String environment, String status) {
        return alertRepository.search(serviceId, environment, status).stream().map(this::toResponse).toList();
    }

    @Transactional
    public AlertResponse acknowledge(String id, String username, String ip) {
        AlertEntity alert = findOrThrow(id);
        String st = alert.getStatus();
        // Decision 2: OPEN ≡ alias of TRIGGERED — acknowledge valid from both
        if (!st.equals(AlertStatus.TRIGGERED.name()) && !st.equals(AlertStatus.OPEN.name())) {
            auditLogService.recordFailure(AuditRecord.of(username, "ACKNOWLEDGE_ALERT", "alerts/" + id, ip, "FAILED"));
            throw new ConflictException("ALERT_NOT_ACKNOWLEDGEABLE",
                    "An alert can only be acknowledged while TRIGGERED/OPEN, was " + st);
        }
        alert.setStatus(AlertStatus.ACKNOWLEDGED.name());
        alert.setAcknowledgedAt(Instant.now());
        alertRepository.save(alert);
        auditLogService.recordSuccess(AuditRecord.of(username, "ACKNOWLEDGE_ALERT", "alerts/" + id, ip, "SUCCESS"));
        return toResponse(alert);
    }

    @Transactional
    public AlertResponse resolve(String id, String username, String ip) {
        AlertEntity alert = findOrThrow(id);
        if (!alert.getStatus().equals(AlertStatus.ACKNOWLEDGED.name())) {
            auditLogService.recordFailure(AuditRecord.of(username, "RESOLVE_ALERT", "alerts/" + id, ip, "FAILED"));
            throw new ConflictException("ALERT_NOT_RESOLVABLE",
                    "An alert can only be resolved after it is ACKNOWLEDGED, was " + alert.getStatus());
        }
        alert.setStatus(AlertStatus.RESOLVED.name());
        alert.setResolvedAt(Instant.now());
        alertRepository.save(alert);
        auditLogService.recordSuccess(AuditRecord.of(username, "RESOLVE_ALERT", "alerts/" + id, ip, "SUCCESS"));
        return toResponse(alert);
    }

    private AlertEntity findOrThrow(String id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_NOT_FOUND", "Alert not found: " + id));
    }

    private AlertResponse toResponse(AlertEntity a) {
        return new AlertResponse(a.getId(), a.getRuleId(), a.getServiceId(), a.getEnvironment(),
                a.getWindowStart() == null ? null : a.getWindowStart().toString(),
                a.getSeverity(), a.getStatus(),
                a.getTriggeredAt() == null ? null : a.getTriggeredAt().toString(),
                a.getAcknowledgedAt() == null ? null : a.getAcknowledgedAt().toString(),
                a.getResolvedAt() == null ? null : a.getResolvedAt().toString(),
                a.getOccurrenceCount());
    }
}