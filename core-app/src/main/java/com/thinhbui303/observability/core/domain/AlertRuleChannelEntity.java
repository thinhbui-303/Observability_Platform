package com.thinhbui303.observability.core.domain;

import com.thinhbui303.observability.common.ChannelType;
import jakarta.persistence.*;

@Entity
@Table(name = "alert_rule_channels")
public class AlertRuleChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "alert_rule_id", nullable = false)
    private AlertRuleEntity alertRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private ChannelType channelType;

    @Column(length = 255)
    private String target;

    @Column(nullable = false)
    private boolean enabled = true;

    public AlertRuleChannelEntity() {}
    // Standard getters/setters
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public AlertRuleEntity getAlertRule() { return alertRule; } public void setAlertRule(AlertRuleEntity alertRule) { this.alertRule = alertRule; }
    public ChannelType getChannelType() { return channelType; } public void setChannelType(ChannelType channelType) { this.channelType = channelType; }
    public String getTarget() { return target; } public void setTarget(String target) { this.target = target; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; }
}