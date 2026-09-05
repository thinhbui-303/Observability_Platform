package com.thinhbui303.observability.analytics.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import com.thinhbui303.observability.common.CanonicalLogEvent;

/**
 * Extracts event-time from the "timestamp" field of CanonicalLogEvent JSON.
 * 
 * Note: Kafka Streams invokes TimestampExtractor BEFORE value deserialization,
 * so record.value() is raw bytes. We parse JSON directly here.
 */
public class CanonicalLogEventTimestampExtractor implements TimestampExtractor {

    private static final Logger log = LoggerFactory.getLogger(CanonicalLogEventTimestampExtractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() != null) {
            try {
                if (record.value() instanceof CanonicalLogEvent logEvent) {
                    if (logEvent.timestamp() != null) {
                        return logEvent.timestamp().toEpochMilli();
                    }
                } else {
                    byte[] bytes;
                    if (record.value() instanceof byte[] b) {
                        bytes = b;
                    } else if (record.value() instanceof String s) {
                        bytes = s.getBytes();
                    } else {
                        bytes = record.value().toString().getBytes();
                    }
                    JsonNode node = mapper.readTree(bytes);
                    if (node.has("timestamp") && !node.get("timestamp").isNull()) {
                        String timestampStr = node.get("timestamp").asText();
                        return Instant.parse(timestampStr).toEpochMilli();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract timestamp from record, falling back to partition time: {}", e.getMessage());
            }
        }
        // Fallback: use Kafka's partition timestamp
        return partitionTime > 0 ? partitionTime : System.currentTimeMillis();
    }
}
