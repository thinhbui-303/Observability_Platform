package com.thinhbui303.observability.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.config.JsonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CanonicalLogEventContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        JsonConfig jsonConfig = new JsonConfig();
        objectMapper = jsonConfig.objectMapper();
    }

    @Test
    void testSerializationAndDeserializationContract() throws Exception {
        CanonicalLogEvent originalEvent = new CanonicalLogEvent(
                1,
                "evt-123",
                Instant.parse("2026-09-05T10:00:00Z"),
                "test-service",
                "production",
                "INFO",
                "This is a test message",
                "trace-1",
                "span-1",
                "host-1",
                "inst-1",
                "TestLogger",
                "POST",
                "/api/test",
                200,
                150L,
                Map.of("key1", "value1", "key2", "value2")
        );

        String json = objectMapper.writeValueAsString(originalEvent);
        
        // Ensure durationMs is a number in JSON (not string) and timestamp is ISO-8601 string
        assertThat(json).contains("\"durationMs\":150");
        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"timestamp\":\"2026-09-05T10:00:00Z\"");

        CanonicalLogEvent deserializedEvent = objectMapper.readValue(json, CanonicalLogEvent.class);

        assertThat(deserializedEvent).isEqualTo(originalEvent);
        assertThat(deserializedEvent.durationMs()).isEqualTo(150L);
        assertThat(deserializedEvent.schemaVersion()).isEqualTo(1);
    }
}
