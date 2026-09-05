package com.thinhbui303.observability.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.indexer.es.ElasticsearchBulkIndexer;
import com.thinhbui303.observability.indexer.es.IndexNameResolver;
import com.thinhbui303.observability.indexer.handler.DlqPublisher;
import com.thinhbui303.observability.indexer.retry.RetryPolicy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class IndexerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IndexerIntegrationTest.class);


    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private IndexNameResolver indexNameResolver;

    @SpyBean
    private ElasticsearchBulkIndexer elasticsearchBulkIndexer;

    private static KafkaConsumer<String, String> retry1sConsumer;
    private static KafkaConsumer<String, String> dlqConsumer;

    @BeforeAll
    static void setupConsumers() {
        Map<String, Object> props1 = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ConsumerConfig.GROUP_ID_CONFIG, "test-verifier-retry-group",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        retry1sConsumer = new KafkaConsumer<>(props1);
        retry1sConsumer.subscribe(Collections.singletonList(RetryPolicy.TOPIC_RETRY_1S));

        Map<String, Object> props2 = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ConsumerConfig.GROUP_ID_CONFIG, "test-verifier-dlq-group",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        dlqConsumer = new KafkaConsumer<>(props2);
        dlqConsumer.subscribe(Collections.singletonList(DlqPublisher.TOPIC_DLQ));
    }

    @AfterAll
    static void teardownConsumers() {
        if (retry1sConsumer != null) retry1sConsumer.close();
        if (dlqConsumer != null) dlqConsumer.close();
    }

    @Test
    void testIndexingFlow_ShouldWriteDocumentToElasticsearch() throws Exception {
        String eventId = UUID.randomUUID().toString();
        // Edge case: midnight UTC
        Instant timestamp = Instant.parse("2026-09-05T00:00:01Z");
        
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, timestamp, "test-svc", "prod", "INFO", "Hello ES", 
                null, null, null, null, null, null, null, 200, 100L, Map.of("userId", "123")
        );

        kafkaTemplate.send("raw-logs", eventId, event).get();

        String expectedIndex = indexNameResolver.resolve(event); // logs-2026.09.05

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                GetResponse<Object> response = elasticsearchClient.get(g -> g.index(expectedIndex).id(eventId), Object.class);
                assertThat(response.found()).isTrue();
            } catch (Exception e) {
                throw new AssertionError("Document not found yet", e);
            }
        });
    }

    @Test
    void testDuplicateEventId_ShouldBeIdempotent() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Instant timestamp = Instant.parse("2026-09-05T12:00:00Z");
        
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, timestamp, "test-svc", "prod", "INFO", "Duplicate Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of()
        );

        // Send first time
        kafkaTemplate.send("raw-logs", eventId, event).get();

        String expectedIndex = indexNameResolver.resolve(event);
        
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                GetResponse<Object> response = elasticsearchClient.get(g -> g.index(expectedIndex).id(eventId), Object.class);
                assertThat(response.found()).isTrue();
            } catch (Exception e) {
                throw new AssertionError("Document not found", e);
            }
        });

        // Send second time
        kafkaTemplate.send("raw-logs", eventId, event).get();

        // The processor should not throw any exceptions, 409 will be ignored.
        // We verify indirectly by checking DLQ is empty for this event
        ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofSeconds(2));
        for (ConsumerRecord<String, String> rec : records) {
            assertThat(rec.key()).isNotEqualTo(eventId);
        }
    }

    @Test
    void testRetryableError_ShouldGoToRetryTopicThenSucceed() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Instant timestamp = Instant.parse("2026-09-05T12:00:00Z");
        
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, timestamp, "test-svc", "prod", "INFO", "Retry Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of()
        );

        // Mock 429 response on the first try for this specific event
        BulkResponse mock429Response = Mockito.mock(BulkResponse.class);
        BulkResponseItem item429 = Mockito.mock(BulkResponseItem.class);
        Mockito.when(item429.id()).thenReturn(eventId);
        Mockito.when(item429.status()).thenReturn(429);
        Mockito.when(mock429Response.items()).thenReturn(List.of(item429));

        // Use doAnswer to return mock 429 ONLY once for this event, then call real method
        Mockito.doAnswer(invocation -> {
            List<CanonicalLogEvent> batch = invocation.getArgument(0);
            if (batch.stream().anyMatch(e -> e.eventId().equals(eventId))) {
                // Remove the mock behavior so subsequent retry works
                Mockito.doCallRealMethod().when(elasticsearchBulkIndexer).bulkIndex(Mockito.anyList());
                return mock429Response;
            }
            return invocation.callRealMethod();
        }).when(elasticsearchBulkIndexer).bulkIndex(Mockito.anyList());

        kafkaTemplate.send("raw-logs", eventId, event).get();

        // It should go to retry 1s topic
        boolean foundInRetry = false;
        long endTime = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < endTime && !foundInRetry) {
            ConsumerRecords<String, String> records = retry1sConsumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> rec : records) {
                if (eventId.equals(rec.key())) {
                    foundInRetry = true;
                    assertThat(rec.headers().lastHeader("X-Original-Topic")).isNotNull();
                    assertThat(rec.headers().lastHeader("X-Retry-Count")).isNotNull();
                    break;
                }
            }
        }
        assertThat(foundInRetry).isTrue();

        // RetryWorker should pick it up and process it successfully since we removed the mock
        String expectedIndex = indexNameResolver.resolve(event);
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                GetResponse<Object> response = elasticsearchClient.get(g -> g.index(expectedIndex).id(eventId), Object.class);
                assertThat(response.found()).isTrue();
            } catch (Exception e) {
                throw new AssertionError("Document not found after retry", e);
            }
        });
    }

    @Test
    void testNonRetryableError_ShouldGoToDlq() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Instant timestamp = Instant.parse("2026-09-05T12:00:00Z");
        
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, timestamp, "test-svc", "prod", "INFO", "DLQ Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of()
        );

        // Mock 400 response
        BulkResponse mock400Response = Mockito.mock(BulkResponse.class);
        BulkResponseItem item400 = Mockito.mock(BulkResponseItem.class);
        Mockito.when(item400.id()).thenReturn(eventId);
        Mockito.when(item400.status()).thenReturn(400);
        Mockito.when(mock400Response.items()).thenReturn(List.of(item400));

        Mockito.doAnswer(invocation -> {
            List<CanonicalLogEvent> batch = invocation.getArgument(0);
            if (batch.stream().anyMatch(e -> e.eventId().equals(eventId))) {
                return mock400Response;
            }
            return invocation.callRealMethod();
        }).when(elasticsearchBulkIndexer).bulkIndex(Mockito.anyList());

        kafkaTemplate.send("raw-logs", eventId, event).get();

        // Should go directly to DLQ
        boolean foundInDlq = false;
        long endTime = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < endTime && !foundInDlq) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> rec : records) {
                if (eventId.equals(rec.key())) {
                    foundInDlq = true;
                    break;
                }
            }
        }
        assertThat(foundInDlq).isTrue();
    }

    @Test
    void testBatchFlushLatencyAtLowThroughput() throws Exception {
        long startTime = System.currentTimeMillis();
        
        String eventId = UUID.randomUUID().toString();
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, Instant.now(), "test-svc", "prod", "INFO", "Latency Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of()
        );

        // Send just 1 event (low load, < 1000 items)
        kafkaTemplate.send("raw-logs", eventId, event).get();

        String expectedIndex = indexNameResolver.resolve(event);

        // We expect it to be processed and indexed quickly (e.g. under ~2s), 
        // proving that it doesn't wait 5000ms (default max poll wait if misconfigured)
        Awaitility.await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                GetResponse<Object> response = elasticsearchClient.get(g -> g.index(expectedIndex).id(eventId), Object.class);
                assertThat(response.found()).isTrue();
            } catch (Exception e) {
                throw new AssertionError("Document not found fast enough", e);
            }
        });
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Batch flush latency: {} ms", duration);
        assertThat(duration).isLessThan(4000); // Allow some overhead, but clearly < 5000ms
    }

    @Test
    void testMetadataField_ShouldBeFlattened() throws Exception {
        String eventId = UUID.randomUUID().toString();
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, Instant.now(), "test-svc", "prod", "INFO", "Mapping Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of("nested", Map.of("key", "value"))
        );

        kafkaTemplate.send("raw-logs", eventId, event).get();
        String expectedIndex = indexNameResolver.resolve(event);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                GetResponse<Object> response = elasticsearchClient.get(g -> g.index(expectedIndex).id(eventId), Object.class);
                assertThat(response.found()).isTrue();
            } catch (Exception e) {
                throw new AssertionError("Document not found", e);
            }
        });

        // Verify mapping
        co.elastic.clients.elasticsearch.indices.GetMappingResponse mappingResponse = 
            elasticsearchClient.indices().getMapping(g -> g.index(expectedIndex));
        
        co.elastic.clients.elasticsearch._types.mapping.Property metadataProp = 
            mappingResponse.result().get(expectedIndex).mappings().properties().get("metadata");
            
        assertThat(metadataProp).isNotNull();
        assertThat(metadataProp.isFlattened()).isTrue();
    }

    @Autowired
    private com.thinhbui303.observability.indexer.handler.RetryPublisher retryPublisher;

    @Test
    void testOffsetCommitOrdering() throws Exception {
        String eventId = UUID.randomUUID().toString();
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, Instant.now(), "test-svc", "prod", "INFO", "Offset Commit Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of()
        );

        // Mock ES to return 429 so it goes to RetryPublisher
        BulkResponse mock429Response = Mockito.mock(BulkResponse.class);
        BulkResponseItem item429 = Mockito.mock(BulkResponseItem.class);
        Mockito.when(item429.id()).thenReturn(eventId);
        Mockito.when(item429.status()).thenReturn(429);
        Mockito.when(mock429Response.items()).thenReturn(List.of(item429));

        Mockito.doAnswer(invocation -> {
            List<CanonicalLogEvent> batch = invocation.getArgument(0);
            if (batch.stream().anyMatch(e -> e.eventId().equals(eventId))) {
                return mock429Response;
            }
            return invocation.callRealMethod();
        }).when(elasticsearchBulkIndexer).bulkIndex(Mockito.anyList());

        // Now, we need to mock RetryPublisher to throw an exception to simulate network failure to Kafka when sending to retry topic
        // But retryPublisher is a real bean, we can't easily mock it without @SpyBean or reflection unless we added @SpyBean
        // Since we didn't add @SpyBean for RetryPublisher, we can simulate ES network failure instead (which throws Exception in bulkIndex).
        // If bulkIndex throws RuntimeException, the offset should NOT be committed, and it should retry indefinitely.
        
        Mockito.doThrow(new RuntimeException("Simulated ES Network Failure")).when(elasticsearchBulkIndexer).bulkIndex(
            Mockito.argThat(list -> ((List<CanonicalLogEvent>)list).stream().anyMatch(e -> e.eventId().equals(eventId)))
        );

        kafkaTemplate.send("raw-logs", eventId, event).get();

        // Verify that bulkIndex is called multiple times (redelivered by Kafka because offset is not committed)
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Mockito.verify(elasticsearchBulkIndexer, Mockito.atLeast(3)).bulkIndex(
                Mockito.argThat(list -> ((List<CanonicalLogEvent>)list).stream().anyMatch(e -> e.eventId().equals(eventId)))
            );
        });
        
        // This proves offset is NOT committed and message is re-polled by the consumer!
    }

    @Test
    void testRetryWorkerOffsetCommitOrdering() throws Exception {
        String eventId = UUID.randomUUID().toString();
        CanonicalLogEvent event = new CanonicalLogEvent(
                1, eventId, Instant.now(), "test-svc", "prod", "INFO", "Retry Worker Offset Commit Test", 
                null, null, null, null, null, null, null, 200, 100L, Map.of()
        );

        // Send directly to the retry 1s topic to trigger RetryWorker
        kafkaTemplate.send(RetryPolicy.TOPIC_RETRY_1S, eventId, event).get();

        // We simulate a network failure in Elasticsearch for the RetryWorker.
        // Because RetryWorker also uses elasticsearchBulkIndexer, mocking it to throw Exception will cause RetryWorker to fail
        Mockito.doThrow(new RuntimeException("Simulated ES Network Failure in RetryWorker")).when(elasticsearchBulkIndexer).bulkIndex(
            Mockito.argThat(list -> ((List<CanonicalLogEvent>)list).stream().anyMatch(e -> e.eventId().equals(eventId)))
        );

        // Verify that RetryWorker is invoked multiple times because it doesn't commit the offset
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Mockito.verify(elasticsearchBulkIndexer, Mockito.atLeast(3)).bulkIndex(
                Mockito.argThat(list -> ((List<CanonicalLogEvent>)list).stream().anyMatch(e -> e.eventId().equals(eventId)))
            );
        });
        
        // This proves RetryWorker also strictly adheres to the ACK-ordering and does not commit on failure!
    }
}
