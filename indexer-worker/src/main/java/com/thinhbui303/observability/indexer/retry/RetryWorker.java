package com.thinhbui303.observability.indexer.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.indexer.es.ElasticsearchBulkIndexer;
import com.thinhbui303.observability.indexer.handler.ItemResultHandler;
import com.thinhbui303.observability.indexer.handler.RetryPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

@Service
public class RetryWorker {

    private static final Logger log = LoggerFactory.getLogger(RetryWorker.class);

    private final ObjectMapper objectMapper;
    private final ElasticsearchBulkIndexer elasticsearchBulkIndexer;
    private final ItemResultHandler itemResultHandler;
    private final KafkaListenerEndpointRegistry registry;
    private final TaskScheduler taskScheduler;

    public RetryWorker(ObjectMapper objectMapper, 
                       ElasticsearchBulkIndexer elasticsearchBulkIndexer, 
                       ItemResultHandler itemResultHandler,
                       KafkaListenerEndpointRegistry registry,
                       TaskScheduler taskScheduler) {
        this.objectMapper = objectMapper;
        this.elasticsearchBulkIndexer = elasticsearchBulkIndexer;
        this.itemResultHandler = itemResultHandler;
        this.registry = registry;
        this.taskScheduler = taskScheduler;
    }

    // Note: group.id is strictly "retry-worker-group" which is distinct from BatchLogProcessor ("indexer-group").
    // This separation prevents Kafka from mixing up consumer assignments and prevents rebalance storms across contexts.
    @KafkaListener(id = "retry-worker",
                   topics = {RetryPolicy.TOPIC_RETRY_1S, RetryPolicy.TOPIC_RETRY_5S, RetryPolicy.TOPIC_RETRY_30S}, 
                   groupId = "retry-worker-group", 
                   containerFactory = "kafkaListenerContainerFactory")
    public void processRetryBatch(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment, Consumer<?, ?> consumer) {
        if (records.isEmpty()) {
            return;
        }

        log.info("RetryWorker received batch of {} records from topic: {}", records.size(), records.get(0).topic());

        String currentTopic = records.get(0).topic();
        int partition = records.get(0).partition();
        TopicPartition topicPartition = new TopicPartition(currentTopic, partition);
        long delayMs = getDelayMsForTopic(currentTopic);
        
        long expectedProcessTime = records.get(0).timestamp() + delayMs;
        long waitTime = expectedProcessTime - System.currentTimeMillis();
        
        if (waitTime > 0) {
            log.debug("Delaying processing for partition {} by {} ms", topicPartition, waitTime);
            
            MessageListenerContainer container = registry.getListenerContainer("retry-worker");
            if (container != null) {
                container.pausePartition(topicPartition);
                
                // Seek back to the minimum offset in this batch for this partition so it is repolled when resumed
                long minOffset = records.stream()
                                        .filter(r -> r.topic().equals(currentTopic) && r.partition() == partition)
                                        .mapToLong(ConsumerRecord::offset)
                                        .min().getAsLong();
                consumer.seek(topicPartition, minOffset);
                
                taskScheduler.schedule(() -> {
                    log.debug("Resuming partition {}", topicPartition);
                    container.resumePartition(topicPartition);
                }, Instant.now().plusMillis(waitTime));
                
                return; // Do not acknowledge, let it be repolled later
            }
        }

        List<CanonicalLogEvent> events = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, String> record = records.get(i);
            try {
                CanonicalLogEvent event = objectMapper.readValue(record.value(), CanonicalLogEvent.class);
                events.add(event);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse log event in RetryWorker", e);
            }
        }

        try {
            co.elastic.clients.elasticsearch.core.BulkResponse response = elasticsearchBulkIndexer.bulkIndex(events);
            if (response != null && !records.isEmpty()) {
                ConsumerRecord<String, String> firstRecord = records.get(0);
                String originalTopic = extractHeaderString(firstRecord, RetryPublisher.HEADER_ORIGINAL_TOPIC, currentTopic);
                int originalPartition = extractHeaderInt(firstRecord, RetryPublisher.HEADER_ORIGINAL_PARTITION, firstRecord.partition());
                long originalOffset = extractHeaderLong(firstRecord, RetryPublisher.HEADER_ORIGINAL_OFFSET, firstRecord.offset());
                int currentRetryCount = extractHeaderInt(firstRecord, RetryPublisher.HEADER_RETRY_COUNT, 0);
                long firstFailedAt = extractHeaderLong(firstRecord, RetryPublisher.HEADER_FIRST_FAILED_AT, System.currentTimeMillis());

                itemResultHandler.handleBulkResult(response.items(), events, 
                        originalTopic, originalPartition, originalOffset, 
                        currentRetryCount, firstFailedAt);
            }
        } catch (Exception e) {
            log.error("Network error communicating with Elasticsearch in RetryWorker", e);
            throw new RuntimeException("Elasticsearch cluster unavailable", e);
        }

        // BLOCKER from LLD: Commit offset ONLY AFTER all Retry/DLQ messages have been fully ACKed by the broker.
        acknowledgment.acknowledge();
        log.debug("Retry batch processing complete and offset committed.");
    }

    private String extractHeaderString(ConsumerRecord<?, ?> record, String headerKey, String defaultValue) {
        Header header = record.headers().lastHeader(headerKey);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : defaultValue;
    }

    private int extractHeaderInt(ConsumerRecord<?, ?> record, String headerKey, int defaultValue) {
        String val = extractHeaderString(record, headerKey, null);
        try {
            return val != null ? Integer.parseInt(val) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long extractHeaderLong(ConsumerRecord<?, ?> record, String headerKey, long defaultValue) {
        String val = extractHeaderString(record, headerKey, null);
        try {
            return val != null ? Long.parseLong(val) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long getDelayMsForTopic(String topic) {
        if (RetryPolicy.TOPIC_RETRY_1S.equals(topic)) {
            return 1000L;
        } else if (RetryPolicy.TOPIC_RETRY_5S.equals(topic)) {
            return 5000L;
        } else if (RetryPolicy.TOPIC_RETRY_30S.equals(topic)) {
            return 30000L;
        }
        return 0L;
    }
}
