package com.thinhbui303.observability.alert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "alerts")
public class AlertEntity {
    @Id
    @Column(name = "id") private String id;
    @Column(name = "rule_id") private Long ruleId;
    @Column(name = "service_id") private String serviceId;
    @Column(name = "environment") private String environment;
    @Column(name = "window_start") private Instant windowStart;
    @Column(name = "severity") private String severity;
    @Column(name = "status") private String status;
    @Column(name = "triggered_at") private Instant triggeredAt;
    @Column(name = "acknowledged_at") private Instant acknowledgedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "occurrence_count") private int occurrenceCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(Instant triggeredAt) { this.triggeredAt = triggeredAt; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }
}
