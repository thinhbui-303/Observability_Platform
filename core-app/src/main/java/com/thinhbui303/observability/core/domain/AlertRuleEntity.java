package com.thinhbui303.observability.core.domain;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.common.Severity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "alert_rules")
public class AlertRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    // LLD: object relation ONLY — no parallel raw serviceId string
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ServiceEntity service;

    @Column(nullable = false, length = 20)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 50)
    private ConditionType conditionType;

    @Column(name = "threshold_value", nullable = false)
    private Integer thresholdValue;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds;

    @Column(name = "condition_value", length = 255)
    private String conditionValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserEntity createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // The ONLY cascade in the model: channels live & die with their rule
    @OneToMany(mappedBy = "alertRule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlertRuleChannelEntity> channels = new ArrayList<>();

    public AlertRuleEntity() {}
    public void addChannel(AlertRuleChannelEntity c) { c.setAlertRule(this); channels.add(c); }
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getRuleName() { return ruleName; } public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public ServiceEntity getService() { return service; } public void setService(ServiceEntity service) { this.service = service; }
    public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
    public ConditionType getConditionType() { return conditionType; } public void setConditionType(ConditionType conditionType) { this.conditionType = conditionType; }
    public Integer getThresholdValue() { return thresholdValue; } public void setThresholdValue(Integer thresholdValue) { this.thresholdValue = thresholdValue; }
    public Integer getWindowSeconds() { return windowSeconds; } public void setWindowSeconds(Integer windowSeconds) { this.windowSeconds = windowSeconds; }
    public String getConditionValue() { return conditionValue; } public void setConditionValue(String conditionValue) { this.conditionValue = conditionValue; }
    public Severity getSeverity() { return severity; } public void setSeverity(Severity severity) { this.severity = severity; }
    public boolean isEnabled() { return isEnabled; } public void setEnabled(boolean enabled) { isEnabled = enabled; }
    public UserEntity getCreatedBy() { return createdBy; } public void setCreatedBy(UserEntity createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<AlertRuleChannelEntity> getChannels() { return channels; } public void setChannels(List<AlertRuleChannelEntity> channels) { this.channels = channels; }
}