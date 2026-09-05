package com.thinhbui303.observability.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
public class AlertConsumerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private String serviceId;
    private String ruleName;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        serviceId = "test-alert-svc-" + suffix;
        ruleName = "test-alert-rule-" + suffix;
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, 'test-team', 'production', 'ACTIVE')",
                serviceId, serviceId);
        jdbcTemplate.update("INSERT INTO alert_rules (rule_name, service_id, environment, condition_type, threshold_value, window_seconds, severity, is_enabled) VALUES (?, ?, 'production', 'ERROR_SPIKE', 5, 60, 'HIGH', TRUE)",
                ruleName, serviceId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM alerts WHERE rule_id IN (SELECT id FROM alert_rules WHERE rule_name = ?)", ruleName);
        jdbcTemplate.update("DELETE FROM alert_rules WHERE rule_name = ?", ruleName);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", serviceId);
    }

    @Test
    void testAlertPersistence_ShouldWriteToPostgreSQL() throws Exception {
        String alertId = "test-alert-" + UUID.randomUUID();
        Instant windowStart = Instant.parse("2026-09-05T10:15:00Z");
        publish(newAlert(ruleId(), alertId, windowStart));

        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(countOf(alertId)).isEqualTo(1));

        assertThat(jdbcTemplate.queryForObject("SELECT rule_id FROM alerts WHERE id = ?", Long.class, alertId))
                .isEqualTo(ruleId());
        assertThat(jdbcTemplate.queryForObject("SELECT service_id FROM alerts WHERE id = ?", String.class, alertId))
                .isEqualTo(serviceId);
        assertThat(jdbcTemplate.queryForObject("SELECT environment FROM alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("production");
        assertThat(jdbcTemplate.queryForObject("SELECT severity FROM alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("HIGH");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("TRIGGERED");
        assertThat(windowStartOf(alertId).toInstant()).isEqualTo(windowStart);
        assertThat(jdbcTemplate.queryForObject("SELECT acknowledged_at FROM alerts WHERE id = ?", Timestamp.class, alertId))
                .isNull();
        assertThat(occurrenceCountOf(alertId)).isEqualTo(1);
    }

    @Test
    void testDuplicateAlertId_ShouldBeIdempotent() throws Exception {
        String alertId = "test-alert-" + UUID.randomUUID();
        Instant windowStart = Instant.parse("2026-09-05T14:30:00Z");
        CanonicalAlertEvent event = newAlert(ruleId(), alertId, windowStart);

        publish(event);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(countOf(alertId)).isEqualTo(1);
            assertThat(occurrenceCountOf(alertId)).isEqualTo(1);
        });

        publish(event);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(countOf(alertId)).isEqualTo(1);
            assertThat(occurrenceCountOf(alertId)).isEqualTo(1);
        });
    }

    @Test
    void testDuplicateWindowEmit_ShouldIncrementOccurrenceCount() throws Exception {
        String alertIdA = "test-alert-" + UUID.randomUUID();
        String alertIdB = "test-alert-" + UUID.randomUUID();
        Instant windowStart = Instant.parse("2026-09-05T16:45:00Z");
        long ruleId = ruleId();

        publish(newAlert(ruleId, alertIdA, windowStart));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(countOf(alertIdA)).isEqualTo(1);
            assertThat(occurrenceCountOf(alertIdA)).isEqualTo(1);
        });

        publish(newAlert(ruleId, alertIdB, windowStart));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(countOf(alertIdA)).isEqualTo(1);
            assertThat(occurrenceCountOf(alertIdA)).isEqualTo(2);
            assertThat(countOf(alertIdB)).isEqualTo(0);
            assertThat(countForBusinessKey(ruleId, windowStart)).isEqualTo(1);
        });
    }

    private long ruleId() {
        return jdbcTemplate.queryForObject("SELECT id FROM alert_rules WHERE rule_name = ?", Long.class, ruleName);
    }

    private CanonicalAlertEvent newAlert(long ruleId, String alertId, Instant windowStart) {
        return new CanonicalAlertEvent(
                alertId, ruleId, serviceId, "production", "HIGH", "TRIGGERED", "ERROR_SPIKE", 60,
                windowStart, 1, Instant.now(), null, null, null, List.of("SLACK"));
    }

    private void publish(CanonicalAlertEvent event) throws Exception {
        waitForConsumerReady();
        kafkaTemplate.send("system-alerts", event.alertId(), JSON.writeValueAsString(event)).get();
    }

    private void waitForConsumerReady() {
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(registry.getListenerContainers()).isNotEmpty();
            for (MessageListenerContainer container : registry.getListenerContainers()) {
                assertThat(container.isRunning()).isTrue();
                if (container instanceof ConcurrentMessageListenerContainer<?, ?> cmlc) {
                    assertThat(cmlc.getAssignedPartitions()).isNotEmpty();
                }
            }
        });
    }

    private int countOf(String alertId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM alerts WHERE id = ?", Integer.class, alertId);
    }

    private int countForBusinessKey(long ruleId, Instant windowStart) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alerts WHERE rule_id = ? AND service_id = ? AND environment = ? AND window_start = ?",
                Integer.class, ruleId, serviceId, "production", Timestamp.from(windowStart));
    }

    private int occurrenceCountOf(String alertId) {
        return jdbcTemplate.queryForObject("SELECT occurrence_count FROM alerts WHERE id = ?", Integer.class, alertId);
    }

    private Timestamp windowStartOf(String alertId) {
        return jdbcTemplate.queryForObject("SELECT window_start FROM alerts WHERE id = ?", Timestamp.class, alertId);
    }
}