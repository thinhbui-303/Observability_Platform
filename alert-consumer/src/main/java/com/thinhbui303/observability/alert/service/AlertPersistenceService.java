package com.thinhbui303.observability.alert.service;

import com.thinhbui303.observability.alert.entity.AlertEntity;
import com.thinhbui303.observability.alert.mapper.AlertMapper;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;

@Service
public class AlertPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AlertPersistenceService.class);

    // DECISION (LLD 10.2 + 11.3, uq_alerts_business_key from V4):
    // A single (rule_id, service_id, environment, window_start) IS one business alert.
    //   - exact duplicate alertId -> rows==0 (ON CONFLICT (id) DO NOTHING) -> idempotent success, no write
    //   - duplicate emit with a NEW alertId but same business key -> unique_violation on
    //     uq_alerts_business_key -> increment occurrence_count on the existing row.
    // PostgreSQL aborts the WHOLE transaction when a statement fails (SQLSTATE 25P02), so the
    // increment after the caught unique_violation must be re-enabled with a SAVEPOINT rollback
    // (the PG idiom for "sub-transaction"). Raw JDBC alone does NOT keep the tx usable here.

    private static final String INSERT_OR_IGNORE =
            "INSERT INTO alerts (id, rule_id, service_id, environment, window_start, " +
            "severity, status, triggered_at, acknowledged_at, resolved_at, occurrence_count) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO NOTHING";

    private static final String INCREMENT_OCCURRENCE =
            "UPDATE alerts SET occurrence_count = occurrence_count + 1 " +
            "WHERE rule_id = ? AND service_id = ? AND environment = ? AND status IN ('TRIGGERED', 'ACKNOWLEDGED')";

    // JdbcTemplate's positional-arg binder cannot infer an SQL type for java.time.Instant.
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private final JdbcTemplate jdbcTemplate;
    private final AlertMapper mapper;

    public AlertPersistenceService(JdbcTemplate jdbcTemplate, AlertMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    @Transactional
    public void persist(CanonicalAlertEvent event) {
        AlertEntity a = mapper.toEntity(event);
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("JdbcTemplate has no DataSource for the transaction connection");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint();
            int rows;
            try {
                rows = jdbcTemplate.update(INSERT_OR_IGNORE,
                        a.getId(), a.getRuleId(), a.getServiceId(), a.getEnvironment(),
                        Timestamp.from(a.getWindowStart()),
                        a.getSeverity(), a.getStatus(), Timestamp.from(a.getTriggeredAt()),
                        toTimestamp(a.getAcknowledgedAt()), toTimestamp(a.getResolvedAt()),
                        a.getOccurrenceCount());
            } catch (DuplicateKeyException ex) {
                // Business-key duplicate (different alertId, same business key): the INSERT raised
                // unique_violation because the conflict was NOT on the (id) arbiter. PostgreSQL
                // aborted the transaction on that statement, so ROLLBACK TO the savepoint re-enables
                // THIS transaction; then bump occurrence_count on the existing business-key row.
                connection.rollback(savepoint);
                int updated = jdbcTemplate.update(INCREMENT_OCCURRENCE,
                        a.getRuleId(), a.getServiceId(), a.getEnvironment());
                log.info("Business-key duplicate for rule {} service {} env {} -> occurrence_count incremented ({} rows)",
                        a.getRuleId(), a.getServiceId(), a.getEnvironment(), updated);
                return;
            }
            if (rows == 0) {
                log.debug("Alert {} already persisted (idempotent skip)", event.alertId());
            } else {
                log.info("Persisted alert {}", event.alertId());
            }
        } catch (SQLException ex) {
            throw new UncategorizedSQLException("Savepoint-backed persistence failed for alert " + event.alertId(),
                    INSERT_OR_IGNORE, ex);
        } finally {
            if (savepoint != null) {
                try {
                    connection.releaseSavepoint(savepoint);
                } catch (SQLException ex) {
                    log.debug("Could not release savepoint for alert {}: {}", event.alertId(), ex.getMessage());
                }
            }
        }
    }
}
