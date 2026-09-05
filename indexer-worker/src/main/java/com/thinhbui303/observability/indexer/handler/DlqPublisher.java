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
public class DlqPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(DlqPublisher.class);
    
    public static final String TOPIC_DLQ = "logs.dlq";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DlqPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(CanonicalLogEvent event, 
                        String originalTopic, int originalPartition, long originalOffset,
                        int retryCount, String errorType, String errorMessage, long firstFailedAt) {
        
        ProducerRecord<String, Object> record = new ProducerRecord<>(TOPIC_DLQ, event.eventId(), event);
        
        record.headers().add(RetryPublisher.HEADER_ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
        record.headers().add(RetryPublisher.HEADER_ORIGINAL_PARTITION, String.valueOf(originalPartition).getBytes(StandardCharsets.UTF_8));
        record.headers().add(RetryPublisher.HEADER_ORIGINAL_OFFSET, String.valueOf(originalOffset).getBytes(StandardCharsets.UTF_8));
        record.headers().add(RetryPublisher.HEADER_RETRY_COUNT, String.valueOf(retryCount).getBytes(StandardCharsets.UTF_8));
        record.headers().add(RetryPublisher.HEADER_ERROR_TYPE, errorType.getBytes(StandardCharsets.UTF_8));
        record.headers().add(RetryPublisher.HEADER_ERROR_MESSAGE, errorMessage.getBytes(StandardCharsets.UTF_8));
        record.headers().add(RetryPublisher.HEADER_FIRST_FAILED_AT, String.valueOf(firstFailedAt).getBytes(StandardCharsets.UTF_8));

        try {
            // BLOCKER: Must call .get() to ensure ACK from broker
            kafkaTemplate.send(record).get();
            log.info("Successfully published to DLQ: {}", event.eventId());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to publish to DLQ for event: {}", event.eventId(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to publish to DLQ", e);
        }
    }
}
