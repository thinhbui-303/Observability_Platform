package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.core.api.dto.AuditLogResponse;
import com.thinhbui303.observability.core.domain.AuditLogEntity;
import com.thinhbui303.observability.core.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditLogQueryService {

    private static final List<String> DEVOPS_SCOPES = List.of("services/", "alert-rules/");

    private final AuditLogRepository repository;

    public AuditLogQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> list(String action, String username, String resourceTarget, boolean devopsScoped) {
        List<AuditLogEntity> rows = repository.search(action, username, resourceTarget);
        // Decision 1: DEVOPS sees ONLY the operational scope (services/*, alert-rules/*) —
        // never permission/user-change or other activity.
        if (devopsScoped) {
            return rows.stream()
                    .filter(r -> DEVOPS_SCOPES.stream().anyMatch(prefix -> r.getResourceTarget().startsWith(prefix)))
                    .map(this::toResponse)
                    .toList();
        }
        return rows.stream().map(this::toResponse).toList();
    }

    private AuditLogResponse toResponse(AuditLogEntity a) {
        return new AuditLogResponse(a.getId(), a.getUsername(), a.getAction(), a.getResourceTarget(),
                a.getIpAddress(), a.getResultStatus(), a.getCreatedAt().toString());
    }
}