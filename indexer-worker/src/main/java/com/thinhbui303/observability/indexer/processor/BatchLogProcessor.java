package com.thinhbui303.observability.indexer.processor;

import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.indexer.es.ElasticsearchBulkIndexer;
import com.thinhbui303.observability.indexer.handler.ItemResultHandler;
import com.thinhbui303.observability.indexer.handler.RetryPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class BatchLogProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchLogProcessor.class);

    private final ObjectMapper objectMapper;
    private final ElasticsearchBulkIndexer elasticsearchBulkIndexer;
    private final ItemResultHandler itemResultHandler;

    public BatchLogProcessor(ObjectMapper objectMapper, 
                             ElasticsearchBulkIndexer elasticsearchBulkIndexer, 
                             ItemResultHandler itemResultHandler) {
        this.objectMapper = objectMapper;
        this.elasticsearchBulkIndexer = elasticsearchBulkIndexer;
        this.itemResultHandler = itemResultHandler;
    }

    // Note: group.id is strictly "indexer-group" to ensure it scales independently of the RetryWorker (which uses "retry-worker-group").
    @KafkaListener(topics = "raw-logs", groupId = "indexer-group", containerFactory = "kafkaListenerContainerFactory")
    public void process(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        if (records.isEmpty()) {
            return;
        }
        
        log.info("Received batch of {} records from topic: {}", records.size(), records.get(0).topic());

        List<CanonicalLogEvent> events = new ArrayList<>();
        
        // We will assume all records in the batch come from the same topic/partition
        // In practice, a batch can span partitions if concurrency=1, but for retry purposes 
        // passing the base partition/topic is a simplification. We can also extract exactly.
        String originalTopic = records.get(0).topic();
        int originalPartition = records.get(0).partition();
        long originalOffsetBase = records.get(0).offset();

        for (ConsumerRecord<String, String> record : records) {
            try {
                CanonicalLogEvent event = objectMapper.readValue(record.value(), CanonicalLogEvent.class);
                events.add(event);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse log event from Kafka record at offset {}", record.offset(), e);
                // Push parsing errors to DLQ directly.
                String messageKey = record.key() != null ? record.key() : "unknown-key";
                itemResultHandler.getDlqPublisher().publishRaw(
                        record.value(), messageKey,
                        originalTopic, originalPartition, record.offset(),
                        "PARSE_ERROR", e.getMessage(), System.currentTimeMillis()
                );
            }
        }

        try {
            BulkResponse response = elasticsearchBulkIndexer.bulkIndex(events);
            if (response != null) {
                // Handle results and push to Retry/DLQ synchronously
                itemResultHandler.handleBulkResult(response.items(), events, 
                        originalTopic, originalPartition, originalOffsetBase, 
                        0, 0L);
            }
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            log.error("Error communicating with Elasticsearch", e);
            // ENTIRE BATCH FAILED without partial response -> Route to retry topics
            for (int i = 0; i < events.size(); i++) {
                CanonicalLogEvent event = events.get(i);
                ConsumerRecord<String, String> originalRecord = records.get(i);
                String messageKey = originalRecord.key() != null ? originalRecord.key() : event.eventId();
                
                int currentRetryCount = 0;
                if (originalRecord.headers().lastHeader(RetryPublisher.HEADER_RETRY_COUNT) != null) {
                    currentRetryCount = Integer.parseInt(new String(originalRecord.headers().lastHeader(RetryPublisher.HEADER_RETRY_COUNT).value()));
                }
                long firstFailedAt = System.currentTimeMillis();
                if (originalRecord.headers().lastHeader(RetryPublisher.HEADER_FIRST_FAILED_AT) != null) {
                    firstFailedAt = Long.parseLong(new String(originalRecord.headers().lastHeader(RetryPublisher.HEADER_FIRST_FAILED_AT).value()));
                }
                
                // Use RetryPublisher to route it to the next retry topic (or DLQ if max retries exceeded)
                String nextTopic = itemResultHandler.getRetryPolicy().getNextRetryTopic(currentRetryCount);
                if (nextTopic != null) {
                    itemResultHandler.getRetryPublisher().publish(
                            nextTopic, event, originalTopic, originalPartition, originalRecord.offset(),
                            currentRetryCount + 1, "CLUSTER_UNAVAILABLE", e.getMessage(), firstFailedAt
                    );
                } else {
                    itemResultHandler.getDlqPublisher().publish(
                            event, originalTopic, originalPartition, originalRecord.offset(),
                            currentRetryCount, "CLUSTER_UNAVAILABLE", e.getMessage(), firstFailedAt
                    );
                }
            }
        }

        // BLOCKER from LLD: Commit offset ONLY AFTER Elasticsearch responds 
        // AND all Retry/DLQ messages have been fully ACKed by the broker.
        acknowledgment.acknowledge();
        log.debug("Batch processing complete and offset committed.");
    }
}
