package com.thinhbui303.observability.alert.mapper;

import com.thinhbui303.observability.alert.entity.AlertEntity;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import org.springframework.stereotype.Component;

// Field mapping: CanonicalAlertEvent -> alerts
//   alertId -> id, ruleId -> rule_id, serviceId -> service_id (the "serviceName" in the
//   Kafka event IS services.id — verified through analytics pipeline), environment -> environment,
//   windowStart -> window_start, severity -> severity, status -> status (default TRIGGERED),
//   triggeredAt -> triggered_at, acknowledgedAt = null, resolvedAt -> resolved_at,
//   occurrenceCount -> occurrence_count (default 1).
// DECISION: description/triggerValue/windowSeconds/triggerLogId/notificationChannels in
// CanonicalAlertEvent have NO column in alerts — they serve display/notification only and are
// intentionally NOT persisted. Do not add columns.
@Component
public class AlertMapper {
    public AlertEntity toEntity(CanonicalAlertEvent e) {
        AlertEntity a = new AlertEntity();
        a.setId(e.alertId());
        a.setRuleId(e.ruleId());
        a.setServiceId(e.serviceId());
        a.setEnvironment(e.environment());
        a.setWindowStart(e.windowStart());
        a.setSeverity(e.severity());
        a.setStatus(e.status() != null ? e.status() : "TRIGGERED");
        a.setTriggeredAt(e.triggeredAt());
        a.setAcknowledgedAt(null);
        a.setResolvedAt(e.resolvedAt());
        a.setOccurrenceCount(e.occurrenceCount() != null && e.occurrenceCount() > 0 ? e.occurrenceCount() : 1);
        return a;
    }
}
