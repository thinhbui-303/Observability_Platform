package com.thinhbui303.observability.core.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_api_keys")
public class ServiceApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Column(name = "key_prefix", nullable = false, length = 12)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 255)
    private String keyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public ServiceApiKeyEntity() {}
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public ServiceEntity getService() { return service; } public void setService(ServiceEntity service) { this.service = service; }
    public String getKeyPrefix() { return keyPrefix; } public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; } public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; } public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; } public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}