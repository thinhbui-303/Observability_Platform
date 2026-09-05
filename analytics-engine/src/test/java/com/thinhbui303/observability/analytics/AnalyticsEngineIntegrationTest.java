package com.thinhbui303.observability.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.analytics.rule.AlertRuleCacheService;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for the Analytics Engine.
 *
 * Connects to real Kafka (localhost:9092) and PostgreSQL (localhost:5432)
 * running via docker-compose, matching the approach used by core-app tests.
 *
 * Each test seeds its own alert_rules with unique service/env combinations
 * to avoid cross-test pollution, and cleans up after itself.
 */
@SpringBootTest
@ActiveProfiles("test")
public class AnalyticsEngineIntegrationTest {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlertRuleCacheService ruleCacheService;

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;

    // Unique test-run prefix to isolate test data
    private String testRunId;
    private static final java.util.concurrent.atomic.AtomicLong timeOffset = new java.util.concurrent.atomic.AtomicLong(0);

    @BeforeEach
    void init() {
        testRunId = UUID.randomUUID().toString().substring(0, 8);

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(producerProps);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + testRunId);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList("system-alerts"));
        // Do initial poll to join group and get partition assignment
        consumer.poll(Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        if (producer != null) producer.close();
        if (consumer != null) consumer.close();

        // Cleanup test rules
        jdbcTemplate.update("DELETE FROM alert_rule_channels WHERE alert_rule_id IN (SELECT id FROM alert_rules WHERE rule_name LIKE ?)", "test-" + testRunId + "%");
        jdbcTemplate.update("DELETE FROM alert_rules WHERE rule_name LIKE ?", "test-" + testRunId + "%");
        jdbcTemplate.update("DELETE FROM services WHERE id LIKE ?", "test-analytics-svc%");
    }

    @Test
    void testCase1_ActiveRule_ThresholdExceeded_ShouldEmitAlert() throws Exception {
        String serviceId = "test-analytics-svc" + testRunId;
        String env = "production";
        Long ruleId = seedRule(serviceId, env, 2, true);

        // Wait for cache to pick up the new rule
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !ruleCacheService.getRules(serviceId, env).isEmpty());

        Instant baseTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(timeOffset.getAndAdd(3600));

        // Produce 4 ERROR logs within a 60-second window
        for (int i = 0; i < 4; i++) {
            produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(i * 10));
        }

        // DUMMY EVENT: Produce 1 INFO log with timestamp far enough in the future
        // to advance Kafka Streams' stream-time past window_end + grace_period (60s + 10s).
        // Without this, suppress(untilWindowCloses) will NEVER close the window
        // because stream-time only advances when new records arrive — not by wall-clock.
        // DO NOT REMOVE THIS LINE even though it looks like a no-op.
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(150));

        // Assert: system-alerts should receive exactly 1 alert
        boolean alertReceived = false;
        long deadline = System.currentTimeMillis() + 30_000; // 30s timeout
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                CanonicalAlertEvent alert = objectMapper.readValue(record.value(), CanonicalAlertEvent.class);
                System.out.println("TEST 1 RECEIVED ALERT: " + record.value());
                if (alert.serviceId().equals(serviceId) && alert.ruleId().equals(ruleId)) {
                    assertThat(alert.occurrenceCount()).isEqualTo(4);
                    assertThat(alert.status()).isEqualTo("TRIGGERED");
                    assertThat(alert.condition()).contains(">= 2");
                    alertReceived = true;
                }
            }
            if (alertReceived) break;
        }

        assertThat(alertReceived)
                .as("Expected alert for rule %d with count >= 2 on service %s", ruleId, serviceId)
                .isTrue();
    }

    @Test
    void testCase2_ActiveRule_BelowThreshold_ShouldNotEmitAlert() throws Exception {
        String serviceId = "test-analytics-svc2-" + testRunId;
        String env = "production";
        seedRule(serviceId, env, 5, true);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !ruleCacheService.getRules(serviceId, env).isEmpty());

        Instant baseTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(timeOffset.getAndAdd(3600));

        // Produce only 2 ERROR logs (below threshold of 5)
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(10));
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(20));

        // DUMMY EVENT to close window (same reason as testCase1)
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(75));

        // Assert: no alert should appear
        boolean alertReceived = false;
        long deadline = System.currentTimeMillis() + 15_000; // 15s timeout
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                CanonicalAlertEvent alert = objectMapper.readValue(record.value(), CanonicalAlertEvent.class);
                if (alert.serviceId().equals(serviceId)) {
                    alertReceived = true;
                }
            }
        }

        assertThat(alertReceived)
                .as("Should NOT receive alert when count < threshold")
                .isFalse();
    }

    @Test
    void testCase3_DisabledRule_ShouldNotEmitAlert() throws Exception {
        String serviceId = "test-analytics-svc3-" + testRunId;
        String env = "production";
        seedRule(serviceId, env, 2, false); // disabled rule

        // Cache should NOT contain disabled rules
        // Give it time to potentially load
        Thread.sleep(3000);
        assertThat(ruleCacheService.getRules(serviceId, env)).isEmpty();

        Instant baseTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(timeOffset.getAndAdd(3600));

        // Produce 4 ERROR logs (well above threshold of 2)
        for (int i = 0; i < 4; i++) {
            produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(i * 10));
        }

        // DUMMY EVENT to close window
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(75));

        // Assert: no alert
        boolean alertReceived = false;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                CanonicalAlertEvent alert = objectMapper.readValue(record.value(), CanonicalAlertEvent.class);
                if (alert.serviceId().equals(serviceId)) {
                    alertReceived = true;
                }
            }
        }

        assertThat(alertReceived)
                .as("Should NOT receive alert when rule is disabled")
                .isFalse();
    }

    @Test
    void testCase6_LateLogPastGracePeriod_ShouldBeIgnored() throws Exception {
        String serviceId = "test-analytics-svc6-" + testRunId;
        String env = "production";
        seedRule(serviceId, env, 3, true); // threshold = 3

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !ruleCacheService.getRules(serviceId, env).isEmpty());

        Instant baseTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(timeOffset.getAndAdd(3600));

        // Produce 2 ERROR logs within the window (below threshold of 3)
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(10));
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(20));

        // DUMMY EVENT: advance stream-time past window_end + grace (60s + 10s)
        // so the window is closed and suppressed with count = 2 (below threshold -> no alert)
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(150));

        // Now produce a LATE log whose event timestamp is back inside the ORIGINAL window,
        // but it arrives after the window has already closed (past grace period).
        // Kafka Streams must DROP it; the window count must stay at 2, so NO alert may be emitted.
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(30));

        // Assert: no alert should appear even though 3 ERROR logs "touch" the window,
        // because the late one arrived past the grace period
        boolean alertReceived = false;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                CanonicalAlertEvent alert = objectMapper.readValue(record.value(), CanonicalAlertEvent.class);
                if (alert.serviceId().equals(serviceId)) {
                    alertReceived = true;
                }
            }
        }

        assertThat(alertReceived)
                .as("Late ERROR log past grace period must NOT be counted in an already-closed window")
                .isFalse();
    }

    private Long seedRule(String serviceId, String env, int threshold, boolean enabled) {
        return seedRuleFull(serviceId, env, "ERROR_SPIKE", null, threshold, enabled);
    }

    private Long seedRuleFull(String serviceId, String env, String conditionType, String conditionValue, int threshold, boolean enabled) {
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, 'test-team', ?, 'ACTIVE') ON CONFLICT DO NOTHING",
                serviceId, serviceId + " name", env);

        String sql = "INSERT INTO alert_rules (rule_name, service_id, environment, condition_type, condition_value, threshold_value, window_seconds, severity, is_enabled) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        Long ruleId = jdbcTemplate.queryForObject(sql, Long.class,
                "test-" + testRunId + "-rule", serviceId, env, conditionType, conditionValue, threshold, 60, "HIGH", enabled);

        jdbcTemplate.update("INSERT INTO alert_rule_channels (alert_rule_id, channel_type, target) VALUES (?, ?, ?)",
                ruleId, "SLACK", "#alerts");

        // Force cache refresh
        ruleCacheService.loadRules();

        return ruleId;
    }

    private void produceLog(String serviceId, String env, String level, Instant timestamp) throws Exception {
        produceLogWithMessage(serviceId, env, level, "Test message", timestamp);
    }

    private void produceLogWithMessage(String serviceId, String env, String level, String message, Instant timestamp) throws Exception {
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, UUID.randomUUID().toString(), timestamp, serviceId, env, level,
                message, null, null, "localhost", "i-test", "logger",
                "GET", "/api/test", 200, 50L, Map.of()
        );

        String jsonPayload = objectMapper.writeValueAsString(event);
        System.out.println("JSON PAYLOAD: " + jsonPayload);

        producer.send(new ProducerRecord<>("raw-logs", serviceId, jsonPayload)).get(); // .get() to ensure delivery
    }
    
    @Test
    void testCase4_PatternMatch_ShouldEmitAlertImmediately() throws Exception {
        String serviceId = "test-analytics-svc4-" + testRunId;
        String env = "production";
        seedRuleFull(serviceId, env, "PATTERN_MATCH", "OutOfMemoryError", 0, true);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !ruleCacheService.getRules(serviceId, env).isEmpty());

        Instant baseTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(timeOffset.getAndAdd(3600));

        // Produce a log that matches the pattern
        produceLogWithMessage(serviceId, env, "ERROR", "System crashed with OutOfMemoryError", baseTime);

        // Assert: alert should be received almost immediately without waiting for window
        boolean alertReceived = false;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !alertReceived) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                CanonicalAlertEvent alert = objectMapper.readValue(record.value(), CanonicalAlertEvent.class);
                if (alert.serviceId().equals(serviceId) && alert.condition().contains("OutOfMemoryError")) {
                    alertReceived = true;
                }
            }
        }

        assertThat(alertReceived).as("Should receive PATTERN_MATCH alert immediately").isTrue();
    }
    
    @Test
    void testCase5_GracePeriod_ShouldIncludeLateLog() throws Exception {
        String serviceId = "test-analytics-svc5-" + testRunId;
        String env = "production";
        seedRule(serviceId, env, 3, true);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !ruleCacheService.getRules(serviceId, env).isEmpty());

        Instant baseTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(timeOffset.getAndAdd(3600));

        // Produce 2 ERROR logs within window
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(10));
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(20));
        
        // Wait real time so Kafka advances wall clock
        Thread.sleep(2000);
        
        // Produce 1 ERROR log that is LATE (timestamp in the original window), but we produce it late
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(30));
        
        // DUMMY EVENT to close window (past grace period)
        produceLog(serviceId, env, "ERROR", baseTime.plusSeconds(75));

        // Assert: alert should be received with count = 3
        boolean alertReceived = false;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !alertReceived) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                CanonicalAlertEvent alert = objectMapper.readValue(record.value(), CanonicalAlertEvent.class);
                if (alert.serviceId().equals(serviceId) && alert.occurrenceCount() >= 3) {
                    alertReceived = true;
                }
            }
        }

        assertThat(alertReceived).as("Should receive alert including late log").isTrue();
    }
}
