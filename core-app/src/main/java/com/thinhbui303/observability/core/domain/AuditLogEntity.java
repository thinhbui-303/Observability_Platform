package com.thinhbui303.observability.core.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_target", nullable = false, length = 100)
    private String resourceTarget;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "result_status", nullable = false, length = 20)
    private String resultStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AuditLogEntity() {}
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; } public void setUsername(String username) { this.username = username; }
    public String getAction() { return action; } public void setAction(String action) { this.action = action; }
    public String getResourceTarget() { return resourceTarget; } public void setResourceTarget(String resourceTarget) { this.resourceTarget = resourceTarget; }
    public String getIpAddress() { return ipAddress; } public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getResultStatus() { return resultStatus; } public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}