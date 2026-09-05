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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// AFTER_CLASS tears this context down (stopping its Kafka consumers) before the other
// class runs, so this live consumer never processes that class's alerts against the shared DB.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class NotificationRedisDownIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @DynamicPropertySource
    static void redisDown(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> "6399");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer server;
    private String serviceId;
    private String serviceId2;
    private String ruleName;
    private String ruleName2;
    private long ruleId;
    private long ruleId2;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        serviceId = "test-notif-svc-" + suffix + "a";
        ruleName = "test-notif-rule-" + suffix + "a";
        serviceId2 = "test-notif-svc-" + suffix + "b";
        ruleName2 = "test-notif-rule-" + suffix + "b";
        seedRule(serviceId, ruleName);
        ruleId = jdbcTemplate.queryForObject("SELECT id FROM alert_rules WHERE rule_name = ?", Long.class, ruleName);
        seedRule(serviceId2, ruleName2);
        ruleId2 = jdbcTemplate.queryForObject("SELECT id FROM alert_rules WHERE rule_name = ?", Long.class, ruleName2);
    }

    private void seedRule(String svc, String rule) {
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, 'test-team', 'production', 'ACTIVE')",
                svc, svc);
        jdbcTemplate.update("INSERT INTO alert_rules (rule_name, service_id, environment, condition_type, threshold_value, window_seconds, severity, is_enabled) VALUES (?, ?, 'production', 'ERROR_SPIKE', 5, 60, 'HIGH', TRUE)",
                rule, svc);
        jdbcTemplate.update(
                "INSERT INTO alert_rule_channels (alert_rule_id, channel_type, target, enabled) VALUES (?, 'WEBHOOK', 'http://mock.local/rd', TRUE)",
                jdbcTemplate.queryForObject("SELECT id FROM alert_rules WHERE rule_name = ?", Long.class, rule));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM alert_rule_channels WHERE alert_rule_id IN (SELECT id FROM alert_rules WHERE rule_name IN (?, ?))", ruleName, ruleName2);
        jdbcTemplate.update("DELETE FROM alerts WHERE rule_id IN (SELECT id FROM alert_rules WHERE rule_name IN (?, ?))", ruleName, ruleName2);
        jdbcTemplate.update("DELETE FROM alert_rules WHERE rule_name IN (?, ?)", ruleName, ruleName2);
        jdbcTemplate.update("DELETE FROM services WHERE id IN (?, ?)", serviceId, serviceId2);
    }

    @Test
    void testRedisDown_ShouldDegradeGracefully() throws Exception {
        server.expect(twice(), requestTo("http://mock.local/rd")).andRespond(withSuccess());

        publish(newAlert(ruleId, serviceId, "test-notif-alert1-" + UUID.randomUUID()));
        Thread.sleep(2000);

        publish(newAlert(ruleId2, serviceId2, "test-notif-alert2-" + UUID.randomUUID()));
        Thread.sleep(2000);

        server.verify();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(registry.getListenerContainers()).isNotEmpty();
            for (MessageListenerContainer container : registry.getListenerContainers()) {
                assertThat(container.isRunning()).isTrue();
            }
        });
    }

    private CanonicalAlertEvent newAlert(long rule, String svc, String alertId) {
        return new CanonicalAlertEvent(
                alertId, rule, svc, "production", "HIGH", "TRIGGERED", "ERROR_SPIKE", 60,
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
