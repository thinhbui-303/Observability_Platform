package com.thinhbui303.observability.alert.service;

import com.thinhbui303.observability.alert.entity.AlertEntity;
import com.thinhbui303.observability.alert.mapper.AlertMapper;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AlertPersistenceService.class);

    // DECISION (LLD 10.2 + 11.3, uq_alerts_business_key from V4):
    // A single (rule_id, service_id, environment, window_start) IS one business alert.
    //   - exact duplicate alertId -> rows==0 (ON CONFLICT (id) DO NOTHING) -> idempotent success, no write
    //   - duplicate emit with a NEW alertId but same business key -> DuplicateKeyException on
    //     uq_alerts_business_key -> increment occurrence_count on the existing row.
    // Raw JDBC (no JPA/Hibernate) keeps the transaction usable after the caught statement failure.

    private static final String INSERT_OR_IGNORE =
            "INSERT INTO alerts (id, rule_id, service_id, environment, window_start, " +
            "severity, status, triggered_at, acknowledged_at, resolved_at, occurrence_count) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO NOTHING";

    private static final String INCREMENT_OCCURRENCE =
            "UPDATE alerts SET occurrence_count = occurrence_count + 1 " +
            "WHERE rule_id = ? AND service_id = ? AND environment = ? AND window_start = ?";

    private final JdbcTemplate jdbcTemplate;
    private final AlertMapper mapper;

    public AlertPersistenceService(JdbcTemplate jdbcTemplate, AlertMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    @Transactional
    public void persist(CanonicalAlertEvent event) {
        AlertEntity a = mapper.toEntity(event);
        int rows;
        try {
            rows = jdbcTemplate.update(INSERT_OR_IGNORE,
                    a.getId(), a.getRuleId(), a.getServiceId(), a.getEnvironment(), a.getWindowStart(),
                    a.getSeverity(), a.getStatus(), a.getTriggeredAt(), a.getAcknowledgedAt(),
                    a.getResolvedAt(), a.getOccurrenceCount());
        } catch (DuplicateKeyException ex) {
            int updated = jdbcTemplate.update(INCREMENT_OCCURRENCE,
                    a.getRuleId(), a.getServiceId(), a.getEnvironment(), a.getWindowStart());
            log.info("Business-key duplicate for rule {} service {} env {} window {} -> occurrence_count incremented ({} rows)",
                    a.getRuleId(), a.getServiceId(), a.getEnvironment(), a.getWindowStart(), updated);
            return;
        }
        if (rows == 0) {
            log.debug("Alert {} already persisted (idempotent skip)", event.alertId());
        } else {
            log.info("Persisted alert {}", event.alertId());
        }
    }
}
