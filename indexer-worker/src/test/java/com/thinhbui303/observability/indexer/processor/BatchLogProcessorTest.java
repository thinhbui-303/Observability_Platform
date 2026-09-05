package com.thinhbui303.observability.indexer.processor;

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
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BatchLogProcessorTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ElasticsearchBulkIndexer elasticsearchBulkIndexer;

    @Mock
    private ItemResultHandler itemResultHandler;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private BatchLogProcessor batchLogProcessor;

    @Test
    void processBatch_ShouldAcknowledgeOnlyAfterHandlingResults() throws Exception {
        // Arrange
        String jsonPayload = "{\"eventId\":\"123\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("raw-logs", 0, 0, "123", jsonPayload);
        CanonicalLogEvent mockEvent = new CanonicalLogEvent(1, "123", Instant.now(), "svc", "prod", "INFO", "msg", null, null, null, null, null, null, null, 200, 100L, Collections.emptyMap());

        when(objectMapper.readValue(jsonPayload, CanonicalLogEvent.class)).thenReturn(mockEvent);

        co.elastic.clients.elasticsearch.core.BulkResponse mockResponse = Mockito.mock(co.elastic.clients.elasticsearch.core.BulkResponse.class);
        when(elasticsearchBulkIndexer.bulkIndex(anyList())).thenReturn(mockResponse);
        when(mockResponse.items()).thenReturn(Collections.emptyList());

        doNothing().when(itemResultHandler).handleBulkResult(anyList(), anyList(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyLong());

        // Act
        batchLogProcessor.process(List.of(record), acknowledgment);

        // Assert - Verify exact order: ES index -> ItemResultHandler (Retry/DLQ) -> Acknowledge
        InOrder inOrder = Mockito.inOrder(elasticsearchBulkIndexer, itemResultHandler, acknowledgment);
        inOrder.verify(elasticsearchBulkIndexer).bulkIndex(anyList());
        inOrder.verify(itemResultHandler).handleBulkResult(anyList(), anyList(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyLong());
        inOrder.verify(acknowledgment).acknowledge();
    }
}
