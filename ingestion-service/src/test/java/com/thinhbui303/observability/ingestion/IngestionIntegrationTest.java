package com.thinhbui303.observability.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.ApiKeyHashUtil;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IngestionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.thinhbui303.observability.ingestion.ratelimit.TokenBucketRateLimiter rateLimiter;

    // Use default localhost properties from application.yml which connect to the running docker containers.

    private Consumer<String, String> kafkaConsumer;
    
    private static boolean dbInitialized = false;

    @BeforeEach
    void setupKafkaConsumer() {
        Map<String, Object> consumerProps = new java.util.HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        // A fresh group per test starting from "latest": the consumer joins before this test
        // produces anything, so it reads ONLY the records written by this test (or later ones).
        // A unique group id also guarantees no leftover member from a previous run can steal
        // partitions or hold up a rebalance.
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "testGroup-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        kafkaConsumer = cf.createConsumer();
        kafkaConsumer.subscribe(Collections.singleton("raw-logs"));
        long deadline = System.currentTimeMillis() + 10000;
        while (kafkaConsumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            kafkaConsumer.poll(Duration.ofMillis(200));
        }
        if (kafkaConsumer.assignment().isEmpty()) {
            throw new IllegalStateException("Consumer could not get an assignment for topic raw-logs within 10s");
        }
    }

    @AfterEach
    void closeKafkaConsumer() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close(Duration.ofSeconds(2));
        }
    }

    @BeforeEach
    void setupDatabase() {
        // Reset the mock behavior for each test since we override it in one of them
        org.mockito.Mockito.reset(rateLimiter);

        if (!dbInitialized) {
            jdbcTemplate.update("DELETE FROM alert_rules WHERE service_id = ?", "test-service");
            jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", "test-service");
            jdbcTemplate.update("DELETE FROM services WHERE id = ?", "test-service");
            jdbcTemplate.update("DELETE FROM alert_rules WHERE service_id = ?", "test-disabled-service");
            jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", "test-disabled-service");
            jdbcTemplate.update("DELETE FROM services WHERE id = ?", "test-disabled-service");

            // Key: test_key_123 -> SHA-256 = 1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136
            jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING", "test-service", "Test Service", "backend-team", "production");
            jdbcTemplate.update("INSERT INTO service_api_keys (service_id, key_prefix, key_hash, created_at) VALUES (?, ?, ?, now())",
                    "test-service", "test_key", ApiKeyHashUtil.hash("test_key_123"));

            // Disabled service: same key format, DIFFERENT id, status DISABLED
            jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, ?, ?, 'DISABLED') ON CONFLICT (id) DO NOTHING",
                    "test-disabled-service", "Test Disabled Service", "backend-team", "production");
            jdbcTemplate.update("INSERT INTO service_api_keys (service_id, key_prefix, key_hash, created_at) VALUES (?, ?, ?, now())",
                    "test-disabled-service", "test_disab", ApiKeyHashUtil.hash("test_disabled_key_123"));
            dbInitialized = true;
        }
    }

    private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> waitForRecord(String expectedMessageSubstring) {
        long endTime = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < endTime) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(kafkaConsumer, Duration.ofMillis(1000));
            for (org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode jsonNode = objectMapper.readTree(record.value());
                    if (jsonNode.get("message").asText().startsWith(expectedMessageSubstring)) {
                        return record;
                    }
                } catch (Exception e) {}
            }
        }
        return null;
    }

    @Test
    void testValidIngestionWithDataMasking() throws Exception {
        String payload = """
                {
                    "timestamp": "2026-09-05T10:00:00Z",
                    "level": "INFO",
                    "message": "User login token=secret123",
                    "metadata": {
                        "userId": "u-001",
                        "apiKey": "some-sensitive-key"
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = waitForRecord("User login token");
        assertThat(record).isNotNull();
        JsonNode jsonNode = objectMapper.readTree(record.value());
        assertThat(jsonNode.get("message").asText()).isEqualTo("User login token=[REDACTED]");
        assertThat(jsonNode.get("metadata").get("apiKey").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void testSecurityBoundarySpoofedServiceName() throws Exception {
        String payload = """
                {
                    "timestamp": "2026-09-05T10:00:00Z",
                    "level": "INFO",
                    "message": "Security test",
                    "serviceName": "bank-service",
                    "environment": "hacked-env"
                }
                """;

        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = waitForRecord("Security test");
        assertThat(record).isNotNull();
        JsonNode jsonNode = objectMapper.readTree(record.value());
        assertThat(jsonNode.get("serviceName").asText()).isEqualTo("test-service");
        assertThat(jsonNode.get("environment").asText()).isEqualTo("production");
    }

    @Test
    void testInvalidApiKey() throws Exception {
        String payload = """
                {
                    "timestamp": "2026-09-05T10:00:00Z",
                    "level": "INFO",
                    "message": "Test"
                }
                """;

        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "wrong_key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testSameEventId() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {
                    "eventId": "%s",
                    "timestamp": "2026-09-05T10:00:00Z",
                    "level": "INFO",
                    "message": "Test Deduplication"
                }
                """.formatted(eventId);

        // Send first
        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        // Send second
        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    void testRateLimitExceeded() throws Exception {
        org.mockito.Mockito.doThrow(new com.thinhbui303.observability.ingestion.ratelimit.RateLimitExceededException("Rate limit exceeded"))
                .when(rateLimiter).checkAllowed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        
        String payload = """
                {
                    "timestamp": "2026-09-05T10:00:00Z",
                    "level": "INFO",
                    "message": "Rate Limit Test"
                }
                """;

        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void testHashConsistency_CoreAppAndIngestionService_ShouldMatch() throws Exception {
        // Hash produced by the shared platform-common util (the same util core-app uses to
        // generate a key) is accepted by ingestion's ApiKeyValidator for the seeded key.
        assertThat(ApiKeyHashUtil.hash("test_key_123")).isEqualTo(
                "1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136");

        String payload = """
                {
                    "timestamp": "2026-09-06T10:00:00Z",
                    "level": "INFO",
                    "message": "Hash consistency"
                }
                """;
        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    void testDisabledService_ShouldRejectIngestion() throws Exception {
        String payload = """
                {
                    "timestamp": "2026-09-06T10:00:00Z",
                    "level": "INFO",
                    "message": "Disabled service rejected"
                }
                """;
        // key hash is valid (test_disabled_key_123 -> stored via ApiKeyHashUtil) but services.status = 'DISABLED'
        mockMvc.perform(post("/api/v1/telemetry/logs")
                        .header("X-API-Key", "test_disabled_key_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }
}
