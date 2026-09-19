package com.thinhbui303.observability.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.core.api.dto.DlqMessageDto;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);
    private static final String DLQ_TOPIC = "logs.dlq";
    private static final String RAW_LOGS_TOPIC = "raw-logs";
    private static final String ADMIN_GROUP_ID = "core-dlq-admin-group";

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Lock dlqLock = new ReentrantLock();

    public DlqService(ConsumerFactory<String, String> consumerFactory,
                      KafkaTemplate<String, Object> kafkaTemplate,
                      AuditLogService auditLogService,
                      ObjectMapper objectMapper) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public List<DlqMessageDto> getDlqMessages(int limit) {
        // Prevent concurrent admin rebalance storms
        dlqLock.lock();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(ADMIN_GROUP_ID, null)) {
            consumer.subscribe(Collections.singletonList(DLQ_TOPIC));
            // First poll to get assignments and initial records
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            
            List<DlqMessageDto> dtos = new ArrayList<>();
            for (ConsumerRecord<String, String> record : records) {
                if (dtos.size() >= limit) break;
                dtos.add(mapToDto(record));
            }
            
            // Note: We DO NOT call consumer.commitSync(). Thus the messages stay in the queue.
            
            // Sort by X-First-Failed-At to ensure oldest first despite multi-partition
            dtos.sort(Comparator.comparing(dto -> 
                    dto.firstFailedAt() != null ? Long.parseLong(dto.firstFailedAt()) : System.currentTimeMillis()
            ));
            
            return dtos;
        } finally {
            dlqLock.unlock();
        }
    }

    public int processDlqMessages(String action, int limit, String username, String ip) {
        dlqLock.lock();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(ADMIN_GROUP_ID, null)) {
            consumer.subscribe(Collections.singletonList(DLQ_TOPIC));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            
            List<ConsumerRecord<String, String>> recordsToProcess = new ArrayList<>();
            for (ConsumerRecord<String, String> record : records) {
                if (recordsToProcess.size() >= limit) break;
                recordsToProcess.add(record);
            }
            
            if (recordsToProcess.isEmpty()) {
                return 0;
            }

            if ("RETRY".equalsIgnoreCase(action)) {
                // Must ensure ACK for the entire batch BEFORE committing offset (Requirement 2)
                for (ConsumerRecord<String, String> record : recordsToProcess) {
                    try {
                        Object val = record.value();
                        if (val instanceof String strVal) {
                            kafkaTemplate.send(RAW_LOGS_TOPIC, record.key(), strVal).get();
                        } else if (val instanceof LinkedHashMap mapVal) { // Fallback if jackson parses to map
                            kafkaTemplate.send(RAW_LOGS_TOPIC, record.key(), val).get();
                        } else if (val instanceof CanonicalLogEvent evt) {
                            kafkaTemplate.send(RAW_LOGS_TOPIC, record.key(), evt).get();
                        } else {
                            // Raw byte array or other
                            kafkaTemplate.send(RAW_LOGS_TOPIC, record.key(), val).get();
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        log.error("Failed to republish DLQ message to raw-logs. Aborting batch.", e);
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        // REQUIREMENT 3: Do not commit sync. Safe to retry whole batch thanks to ES idempotency.
                        auditLogService.recordFailure(AuditRecord.of(username, "RETRY_DLQ_BATCH", "logs.dlq (Partial failure)", ip, "FAILED"));
                        throw new RuntimeException("Partial batch failure. No offsets committed. Try again.", e);
                    }
                }
            }

            // Only commit sync if DISCARD or if ALL RETRY published successfully
            consumer.commitSync();

            // REQUIREMENT 2: Audit log
            String auditAction = "RETRY".equalsIgnoreCase(action) ? "RETRY_DLQ_BATCH" : "DISCARD_DLQ_BATCH";
            String resourceTarget = String.format("logs.dlq [%d records]", recordsToProcess.size());
            auditLogService.recordSuccess(AuditRecord.of(username, auditAction, resourceTarget, ip, "SUCCESS"));
            
            return recordsToProcess.size();
        } finally {
            dlqLock.unlock();
        }
    }

    private DlqMessageDto mapToDto(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        String errorReason = null;
        String firstFailedAt = null;
        
        for (org.apache.kafka.common.header.Header header : record.headers()) {
            String key = header.key();
            String val = new String(header.value(), StandardCharsets.UTF_8);
            headers.put(key, val);
            if ("X-Error-Message".equals(key)) {
                errorReason = val;
            } else if ("X-Error-Type".equals(key) && errorReason == null) {
                errorReason = val;
            } else if ("X-First-Failed-At".equals(key)) {
                firstFailedAt = val;
            }
        }
        
        String payloadStr;
        try {
            payloadStr = record.value() instanceof String ? (String) record.value() : objectMapper.writeValueAsString(record.value());
        } catch (Exception e) {
            payloadStr = String.valueOf(record.value());
        }

        return new DlqMessageDto(
                record.partition(),
                record.offset(),
                record.key(),
                payloadStr,
                headers,
                errorReason != null ? errorReason : "Unknown",
                firstFailedAt
        );
    }
}
