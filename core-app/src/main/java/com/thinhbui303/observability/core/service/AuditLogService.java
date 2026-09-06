package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.core.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    // REQUIRED: joins the caller's transaction — if the mutation rolls back, this row does too (atomic, BR-009).
    @Transactional
    public void recordSuccess(AuditRecord record) {
        repository.save(record.toEntity());
    }

    // REQUIRES_NEW: own transaction, commits independently — survives the outer transaction's rollback
    // when a ConflictException/BadRequestException is thrown AFTER this call. (decision 1, FAILED rows.)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AuditRecord record) {
        repository.save(record.toEntity());
    }
}