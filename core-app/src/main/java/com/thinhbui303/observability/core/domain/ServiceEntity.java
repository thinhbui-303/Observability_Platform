package com.thinhbui303.observability.core.domain;

import com.thinhbui303.observability.common.ServiceStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "services")
public class ServiceEntity {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "team_owner", nullable = false, length = 50)
    private String teamOwner;

    @Column(nullable = false, length = 20)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceStatus status = ServiceStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ServiceEntity() {}
    // Standard getters/setters (codebase style — see UserEntity.java)
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getTeamOwner() { return teamOwner; } public void setTeamOwner(String teamOwner) { this.teamOwner = teamOwner; }
    public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
    public ServiceStatus getStatus() { return status; } public void setStatus(ServiceStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}