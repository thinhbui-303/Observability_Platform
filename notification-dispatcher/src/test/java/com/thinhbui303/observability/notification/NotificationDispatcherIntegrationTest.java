package com.thinhbui303.observability.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// AFTER_CLASS tears this context down (stopping its Kafka consumers) before the RedisDown
// class runs, so this live consumer never processes the RedisDown class's alerts against
// the shared DB/topic (would post to its stale mock + write stray Redis keys).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class NotificationDispatcherIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer server;
    private String serviceId;
    private String ruleName;
    private long ruleId;
    private String environment = "production";

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        serviceId = "test-notif-svc-" + suffix;
        ruleName = "test-notif-rule-" + suffix;
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, 'test-team', 'production', 'ACTIVE')",
                serviceId, serviceId);
        jdbcTemplate.update("INSERT INTO alert_rules (rule_name, service_id, environment, condition_type, threshold_value, window_seconds, severity, is_enabled) VALUES (?, ?, 'production', 'ERROR_SPIKE', 5, 60, 'HIGH', TRUE)",
                ruleName, serviceId);
        ruleId = jdbcTemplate.queryForObject("SELECT id FROM alert_rules WHERE rule_name = ?", Long.class, ruleName);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM alert_rule_channels WHERE alert_rule_id IN (SELECT id FROM alert_rules WHERE rule_name = ?)", ruleName);
        jdbcTemplate.update("DELETE FROM alerts WHERE rule_id IN (SELECT id FROM alert_rules WHERE rule_name = ?)", ruleName);
        jdbcTemplate.update("DELETE FROM alert_rules WHERE rule_name = ?", ruleName);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", serviceId);
        redisTemplate.delete(List.of(cooldownKey(), counterKey()));
    }

    @Test
    void testNotification_FirstAlert_ShouldAcquireLockAndSend() throws Exception {
        seedChannel("WEBHOOK", "http://mock.local/1", true);
        server.expect(requestTo("http://mock.local/1")).andRespond(withSuccess());

        publish(newAlert("test-notif-alert-" + UUID.randomUUID()));

        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cooldownExists()).isTrue());

        Thread.sleep(300);
        server.verify();
    }

    @Test
    void testNotification_WithinCooldown_ShouldSuppressAndIncrementCounter() throws Exception {
        seedChannel("WEBHOOK", "http://mock.local/1", true);

        server.expect(requestTo("http://mock.local/1")).andRespond(withSuccess());
        publish(newAlert("test-notif-alertA-" + UUID.randomUUID()));
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cooldownExists()).isTrue());
        Thread.sleep(300);
        server.verify();

        server.reset();
        publish(newAlert("test-notif-alertB-" + UUID.randomUUID()));
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(counterValue()).isEqualTo(2L));
        assertThat(cooldownExists()).isTrue();
        Thread.sleep(300);
        server.verify();
    }

    @Test
    void testNotification_SendFailure_ShouldReleaseLockImmediately() throws Exception {
        seedChannel("WEBHOOK", "http://mock.local/1", true);

        server.expect(requestTo("http://mock.local/1")).andRespond(withServerError());
        publish(newAlert("test-notif-alertA-" + UUID.randomUUID()));
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cooldownExists()).isFalse());
        Thread.sleep(300);
        server.verify();

        server.reset();
        server.expect(requestTo("http://mock.local/1")).andRespond(withSuccess());
        publish(newAlert("test-notif-alertB-" + UUID.randomUUID()));
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cooldownExists()).isTrue());
        Thread.sleep(300);
        server.verify();
    }

    @Test
    void testNotification_ChannelDisabled_ShouldBeSkipped() throws Exception {
        seedChannel("SLACK", "http://mock.local/slack", true);
        seedChannel("TELEGRAM", "http://mock.local/telegram", false);

        server.expect(requestTo("http://mock.local/slack")).andRespond(withSuccess());
        publish(newAlert("test-notif-alert-" + UUID.randomUUID()));
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cooldownExists()).isTrue());
        Thread.sleep(300);
        server.verify();
    }

    @Test
    void testNotification_WebsocketChannel_ShouldBeSkipped() throws Exception {
        seedChannel("WEBSOCKET", "http://mock.local/ws", true);
        seedChannel("SLACK", "http://mock.local/slack2", true);

        server.expect(requestTo("http://mock.local/slack2")).andRespond(withSuccess());
        publish(newAlert("test-notif-alert-" + UUID.randomUUID()));
        await().atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cooldownExists()).isTrue());
        Thread.sleep(300);
        server.verify();
    }

    @Test
    void testNotification_NoEnabledChannels_ShouldNotSend() throws Exception {
        seedChannel("SLACK", "http://mock.local/none", false);

        publish(newAlert("test-notif-alert-" + UUID.randomUUID()));

        Thread.sleep(1000);
        assertThat(cooldownExists()).isFalse();
        server.verify();
    }

    private void seedChannel(String type, String target, boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO alert_rule_channels (alert_rule_id, channel_type, target, enabled) VALUES (?, ?, ?, ?)",
                ruleId, type, target, enabled);
    }

    private boolean cooldownExists() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey()));
    }

    private Long counterValue() {
        String v = redisTemplate.opsForValue().get(counterKey());
        return v == null ? null : Long.valueOf(v);
    }

    private String cooldownKey() {
        return "alert:cooldown:" + serviceId + ":" + environment + ":" + ruleId;
    }

    private String counterKey() {
        return "alert:counter:" + serviceId + ":" + environment + ":" + ruleId;
    }

    private CanonicalAlertEvent newAlert(String alertId) {
        return new CanonicalAlertEvent(
                alertId, ruleId, serviceId, environment, "HIGH", "TRIGGERED", "ERROR_SPIKE", 60,
                Instant.now().truncatedTo(ChronoUnit.SECONDS), 1, Instant.now(), null, null, null,
                List.of("SLACK"));
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
}
