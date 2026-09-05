package com.thinhbui303.observability.indexer.handler;

import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

@Service
public class RetryPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(RetryPublisher.class);
    
    public static final String HEADER_ORIGINAL_TOPIC = "X-Original-Topic";
    public static final String HEADER_ORIGINAL_PARTITION = "X-Original-Partition";
    public static final String HEADER_ORIGINAL_OFFSET = "X-Original-Offset";
    public static final String HEADER_RETRY_COUNT = "X-Retry-Count";
    public static final String HEADER_ERROR_TYPE = "X-Error-Type";
    public static final String HEADER_ERROR_MESSAGE = "X-Error-Message";
    public static final String HEADER_FIRST_FAILED_AT = "X-First-Failed-At";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public RetryPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String targetTopic, CanonicalLogEvent event, 
                        String originalTopic, int originalPartition, long originalOffset,
                        int retryCount, String errorType, String errorMessage, long firstFailedAt) {
        
        ProducerRecord<String, Object> record = new ProducerRecord<>(targetTopic, event.eventId(), event);
        
        record.headers().add(HEADER_ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_ORIGINAL_PARTITION, String.valueOf(originalPartition).getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_ORIGINAL_OFFSET, String.valueOf(originalOffset).getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_RETRY_COUNT, String.valueOf(retryCount).getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_ERROR_TYPE, errorType.getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_ERROR_MESSAGE, errorMessage.getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_FIRST_FAILED_AT, String.valueOf(firstFailedAt).getBytes(StandardCharsets.UTF_8));

        try {
            // BLOCKER: Must call .get() to ensure ACK from broker
            kafkaTemplate.send(record).get();
            log.info("Successfully published to retry topic: {}", targetTopic);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to publish to retry topic: {}", targetTopic, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to publish to retry topic", e);
        }
    }
}
