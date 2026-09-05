package com.thinhbui303.observability.indexer.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.indexer.es.ElasticsearchBulkIndexer;
import com.thinhbui303.observability.indexer.handler.ItemResultHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RetryWorkerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ElasticsearchBulkIndexer elasticsearchBulkIndexer;

    @Mock
    private ItemResultHandler itemResultHandler;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private KafkaListenerEndpointRegistry registry;

    @Mock
    private org.springframework.scheduling.TaskScheduler taskScheduler;

    @Mock
    private org.apache.kafka.clients.consumer.Consumer<?, ?> consumer;

    @Mock
    private org.springframework.kafka.listener.MessageListenerContainer container;

    @InjectMocks
    private RetryWorker retryWorker;

    @Test
    void processRetryBatch_ShouldAcknowledgeOnlyAfterForwardingToNextTopic_WhenNoDelay() throws Exception {
        // Arrange
        String jsonPayload = "{\"eventId\":\"123\"}";
        // Simulating a record from logs.retry.1s that is already 2 seconds old -> no delay
        ConsumerRecord<String, String> record = new ConsumerRecord<>(RetryPolicy.TOPIC_RETRY_1S, 0, 0, 100L, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "123", jsonPayload, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        record.headers().add("X-Original-Topic", "raw-logs".getBytes());
        record.headers().add("X-Original-Partition", "0".getBytes());
        record.headers().add("X-Original-Offset", "100".getBytes());
        record.headers().add("X-Retry-Count", "1".getBytes());

        // Ensure timestamp + delay < current time
        long mockTimestamp = System.currentTimeMillis() - 2000;
        record = new ConsumerRecord<>(RetryPolicy.TOPIC_RETRY_1S, 0, 0, mockTimestamp, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "123", jsonPayload, record.headers(), java.util.Optional.empty());

        CanonicalLogEvent mockEvent = new CanonicalLogEvent(1, "123", Instant.now(), "svc", "prod", "INFO", "msg", null, null, null, null, null, null, null, 200, 100L, Collections.emptyMap());

        when(objectMapper.readValue(jsonPayload, CanonicalLogEvent.class)).thenReturn(mockEvent);

        co.elastic.clients.elasticsearch.core.BulkResponse mockResponse = Mockito.mock(co.elastic.clients.elasticsearch.core.BulkResponse.class);
        when(elasticsearchBulkIndexer.bulkIndex(anyList())).thenReturn(mockResponse);
        when(mockResponse.items()).thenReturn(Collections.emptyList());

        // We use handleBulkResult which abstracts sending to the next retry topic or DLQ
        doNothing().when(itemResultHandler).handleBulkResult(
                anyList(), anyList(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyLong());

        // Act
        retryWorker.processRetryBatch(List.of(record), acknowledgment, consumer);

        // Assert - Verify exact order: ES index -> ItemResultHandler (which publishes to next topic) -> Acknowledge
        InOrder inOrder = Mockito.inOrder(elasticsearchBulkIndexer, itemResultHandler, acknowledgment);
        inOrder.verify(elasticsearchBulkIndexer).bulkIndex(anyList());
        inOrder.verify(itemResultHandler).handleBulkResult(
                anyList(), anyList(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyLong());
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void processRetryBatch_ShouldPausePartitionAndNotAcknowledge_WhenDelayRequired() throws Exception {
        // Arrange
        String jsonPayload = "{\"eventId\":\"123\"}";
        // Simulating a record from logs.retry.30s that was just created -> 30s delay
        long mockTimestamp = System.currentTimeMillis();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(RetryPolicy.TOPIC_RETRY_30S, 1, 42, mockTimestamp, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "123", jsonPayload, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());

        when(registry.getListenerContainer("retry-worker")).thenReturn(container);

        // Act
        retryWorker.processRetryBatch(List.of(record), acknowledgment, consumer);

        // Assert
        org.apache.kafka.common.TopicPartition expectedTp = new org.apache.kafka.common.TopicPartition(RetryPolicy.TOPIC_RETRY_30S, 1);
        Mockito.verify(container).pausePartition(expectedTp);
        Mockito.verify(consumer).seek(expectedTp, 42L);
        Mockito.verify(taskScheduler).schedule(Mockito.any(Runnable.class), Mockito.any(Instant.class));
        
        // Ensure no processing or ack happened
        Mockito.verify(acknowledgment, Mockito.never()).acknowledge();
        Mockito.verifyNoInteractions(elasticsearchBulkIndexer);
    }

    @Test
    void processRetryBatch_ShouldScheduleResumeForMultiplePartitionsIndependently() throws Exception {
        // Arrange
        String jsonPayload1 = "{\"eventId\":\"1\"}";
        String jsonPayload2 = "{\"eventId\":\"2\"}";
        
        long now = System.currentTimeMillis();
        
        // Batch 1: partition 1, 30s delay
        ConsumerRecord<String, String> record1 = new ConsumerRecord<>(RetryPolicy.TOPIC_RETRY_30S, 1, 10, now, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "1", jsonPayload1, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        
        // Batch 2: partition 2, 30s delay but processed later
        ConsumerRecord<String, String> record2 = new ConsumerRecord<>(RetryPolicy.TOPIC_RETRY_30S, 2, 20, now - 10000, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "2", jsonPayload2, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());

        when(registry.getListenerContainer("retry-worker")).thenReturn(container);

        // Act - Simulate Kafka passing batches from different partitions
        retryWorker.processRetryBatch(List.of(record1), acknowledgment, consumer);
        retryWorker.processRetryBatch(List.of(record2), acknowledgment, consumer);

        // Assert - Both partitions should be paused and scheduled independently
        org.apache.kafka.common.TopicPartition tp1 = new org.apache.kafka.common.TopicPartition(RetryPolicy.TOPIC_RETRY_30S, 1);
        org.apache.kafka.common.TopicPartition tp2 = new org.apache.kafka.common.TopicPartition(RetryPolicy.TOPIC_RETRY_30S, 2);
        
        Mockito.verify(container).pausePartition(tp1);
        Mockito.verify(consumer).seek(tp1, 10L);
        
        Mockito.verify(container).pausePartition(tp2);
        Mockito.verify(consumer).seek(tp2, 20L);
        
        // TaskScheduler should be invoked twice to schedule the resumes
        Mockito.verify(taskScheduler, Mockito.times(2)).schedule(Mockito.any(Runnable.class), Mockito.any(Instant.class));
    }
}
