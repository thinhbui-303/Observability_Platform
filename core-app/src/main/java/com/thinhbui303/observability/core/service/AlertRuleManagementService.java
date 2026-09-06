package com.thinhbui303.observability.core.service;

import com.thinhbui303.observability.common.ConditionType;
import com.thinhbui303.observability.common.Severity;
import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.api.exception.BadRequestException;
import com.thinhbui303.observability.core.api.exception.NotFoundException;
import com.thinhbui303.observability.core.domain.AlertRuleChannelEntity;
import com.thinhbui303.observability.core.domain.AlertRuleEntity;
import com.thinhbui303.observability.core.domain.UserEntity;
import com.thinhbui303.observability.core.repository.AlertRuleRepository;
import com.thinhbui303.observability.core.repository.ServiceRepository;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AlertRuleManagementService {

    private static final String TARGET_PREFIX = "alert-rules/";

    private final AlertRuleRepository ruleRepository;
    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AlertRuleManagementService(AlertRuleRepository ruleRepository,
                                      ServiceRepository serviceRepository,
                                      UserRepository userRepository,
                                      AuditLogService auditLogService) {
        this.ruleRepository = ruleRepository;
        this.serviceRepository = serviceRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public AlertRuleResponse create(CreateAlertRuleRequest req, String username, String ip) {
        validateBusiness(req.conditionType(), req.thresholdValue(), req.windowSeconds(), req.conditionValue(),
                username, ip, "CREATE_ALERT_RULE");

        var rule = new AlertRuleEntity();
        apply(rule, req.ruleName(), req.serviceId(), req.environment(), req.conditionType(),
                req.thresholdValue(), req.windowSeconds(), req.conditionValue(), req.severity(),
                req.isEnabled(), req.notificationChannels());
        userRepository.findByUsername(username).ifPresent(rule::setCreatedBy);
        ruleRepository.save(rule);

        auditLogService.recordSuccess(AuditRecord.of(username, "CREATE_ALERT_RULE", TARGET_PREFIX + rule.getId(), ip, "SUCCESS"));
        return toResponse(rule);
    }

    @Transactional
    public AlertRuleResponse update(Long id, UpdateAlertRuleRequest req, String username, String ip) {
        validateBusiness(req.conditionType(), req.thresholdValue(), req.windowSeconds(), req.conditionValue(),
                username, ip, "UPDATE_ALERT_RULE");

        AlertRuleEntity rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_RULE_NOT_FOUND", "Alert rule not found: " + id));
        apply(rule, req.ruleName(), req.serviceId(), req.environment(), req.conditionType(),
                req.thresholdValue(), req.windowSeconds(), req.conditionValue(), req.severity(),
                req.isEnabled(), req.notificationChannels());
        ruleRepository.save(rule);

        auditLogService.recordSuccess(AuditRecord.of(username, "UPDATE_ALERT_RULE", TARGET_PREFIX + id, ip, "SUCCESS"));
        return toResponse(rule);
    }

    @Transactional
    public AlertRuleResponse setEnabled(Long id, SetAlertRuleEnabledRequest req, String username, String ip) {
        AlertRuleEntity rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_RULE_NOT_FOUND", "Alert rule not found: " + id));
        rule.setEnabled(req.enabled());
        ruleRepository.save(rule);
        auditLogService.recordSuccess(AuditRecord.of(username, "SET_ALERT_RULE_ENABLED", TARGET_PREFIX + id, ip, "SUCCESS"));
        return toResponse(rule);
    }

    @Transactional
    public void delete(Long id, String username, String ip) {
        AlertRuleEntity rule = ruleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ALERT_RULE_NOT_FOUND", "Alert rule not found: " + id));
        ruleRepository.delete(rule);
        auditLogService.recordSuccess(AuditRecord.of(username, "DELETE_ALERT_RULE", TARGET_PREFIX + id, ip, "SUCCESS"));
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> list(String serviceId, String environment) {
        return ruleRepository.search(serviceId, environment).stream().map(this::toResponse).toList();
    }

    private void validateBusiness(ConditionType type, Integer threshold, Integer window, String conditionValue,
                                  String username, String ip, String action) {
        if (type == ConditionType.PATTERN_MATCH) {
            if (conditionValue == null || conditionValue.isBlank()) {
                auditLogService.recordFailure(AuditRecord.of(username, action, TARGET_PREFIX + "?", ip, "FAILED"));
                throw new BadRequestException("PATTERN_MATCH_REQUIRES_CONDITION_VALUE",
                        "PATTERN_MATCH rules require a non-blank conditionValue");
            }
        } else if (type == ConditionType.ERROR_SPIKE) {
            if (threshold == null || threshold <= 0 || window == null || window <= 0) {
                auditLogService.recordFailure(AuditRecord.of(username, action, TARGET_PREFIX + "?", ip, "FAILED"));
                throw new BadRequestException("ERROR_SPIKE_REQUIRES_POSITIVE_VALUES",
                        "ERROR_SPIKE rules require thresholdValue > 0 and windowSeconds > 0");
            }
        }
    }

    private void apply(AlertRuleEntity rule, String ruleName, String serviceId, String environment,
                       ConditionType type, Integer threshold, Integer window, String conditionValue,
                       Severity severity, Boolean isEnabled, List<ChannelRequest> channels) {
        rule.setRuleName(ruleName);
        rule.setService(serviceRepository.findById(serviceId)
                .orElseThrow(() -> new NotFoundException("SERVICE_NOT_FOUND", "Service not found: " + serviceId)));
        rule.setEnvironment(environment);
        rule.setConditionType(type);
        rule.setConditionValue(conditionValue);
        rule.setSeverity(severity);
        rule.setEnabled(isEnabled == null || isEnabled);
        if (type == ConditionType.PATTERN_MATCH) {
            rule.setThresholdValue(threshold == null ? 1 : threshold);
            rule.setWindowSeconds(window == null ? 60 : window);
        } else {
            rule.setThresholdValue(threshold);
            rule.setWindowSeconds(window);
        }
        rule.getChannels().clear();
        if (channels != null) {
            for (ChannelRequest c : channels) {
                AlertRuleChannelEntity ch = new AlertRuleChannelEntity();
                ch.setChannelType(c.channelType());
                ch.setTarget(c.target());
                ch.setEnabled(c.enabled() == null || c.enabled());
                rule.addChannel(ch);
            }
        }
    }

    private AlertRuleResponse toResponse(AlertRuleEntity r) {
        return new AlertRuleResponse(
                r.getId(), r.getRuleName(), r.getService().getId(), r.getEnvironment(),
                r.getConditionType().name(), r.getThresholdValue(), r.getWindowSeconds(),
                r.getConditionValue(), r.getSeverity().name(), r.isEnabled(), r.getCreatedAt().toString(),
                r.getChannels().stream()
                        .map(c -> new AlertRuleChannelResponse(c.getId(), c.getChannelType(), c.getTarget(), c.isEnabled()))
                        .toList());
    }
}
