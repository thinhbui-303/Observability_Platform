package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.common.ApiKeyHashUtil;
import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.api.exception.NotFoundException;
import com.thinhbui303.observability.core.domain.ServiceApiKeyEntity;
import com.thinhbui303.observability.core.domain.ServiceEntity;
import com.thinhbui303.observability.core.repository.ServiceApiKeyRepository;
import com.thinhbui303.observability.core.repository.ServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ServiceManagementService {

    private final ServiceRepository serviceRepository;
    private final ServiceApiKeyRepository serviceApiKeyRepository;
    private final AuditLogService auditLogService;

    public ServiceManagementService(ServiceRepository serviceRepository,
                                    ServiceApiKeyRepository serviceApiKeyRepository,
                                    AuditLogService auditLogService) {
        this.serviceRepository = serviceRepository;
        this.serviceApiKeyRepository = serviceApiKeyRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ServiceCreateResponse create(CreateServiceRequest req, String username, String ip) {
        // Only services.id must be unique (LLD §2) — there is NO business rule on name uniqueness,
        // hence no app-level name check (would be unsound without a DB constraint) and no race window.
        String base = SlugBuilder.slug(req.name());
        String id = base;
        int n = 2;
        while (serviceRepository.existsById(id)) {
            id = base + "-" + n++;
        }

        ServiceEntity svc = new ServiceEntity();
        svc.setId(id);
        svc.setName(req.name());
        svc.setTeamOwner(req.teamOwner());
        svc.setEnvironment(req.environment());
        svc.setCreatedAt(Instant.now());
        serviceRepository.save(svc);

        String plain = ApiKeyGenerator.generate();
        ServiceApiKeyEntity key = new ServiceApiKeyEntity();
        key.setService(svc);
        key.setKeyPrefix(plain.substring(0, 12));
        key.setKeyHash(ApiKeyHashUtil.hash(plain));
        serviceApiKeyRepository.save(key);

        auditLogService.recordSuccess(AuditRecord.of(username, "CREATE_SERVICE", "services/" + id, ip, "SUCCESS"));
        return new ServiceCreateResponse(id, svc.getName(), svc.getEnvironment(), plain, key.getKeyPrefix());
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> list() {
        return serviceRepository.findAll().stream().map(ServiceManagementService::toResponse).toList();
    }

    @Transactional
    public ServiceResponse updateStatus(String id, UpdateServiceStatusRequest req, String username, String ip) {
        ServiceEntity svc = serviceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SERVICE_NOT_FOUND", "Service not found: " + id));
        svc.setStatus(req.status());
        serviceRepository.save(svc);
        auditLogService.recordSuccess(AuditRecord.of(username, "UPDATE_SERVICE_STATUS", "services/" + id, ip, "SUCCESS"));
        return toResponse(svc);
    }

    private static ServiceResponse toResponse(ServiceEntity s) {
        return new ServiceResponse(s.getId(), s.getName(), s.getTeamOwner(), s.getEnvironment(),
                s.getStatus().name(), s.getCreatedAt().toString());
    }
}