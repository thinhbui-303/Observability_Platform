package com.thinhbui303.observability.core.dashboard.service;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KafkaLagService {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagService.class);
    private final KafkaAdmin kafkaAdmin;
    private AdminClient adminClient;

    public KafkaLagService(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @PostConstruct
    public void init() {
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @PreDestroy
    public void close() {
        if (this.adminClient != null) {
            this.adminClient.close();
        }
    }

    public long getConsumerGroupLag(String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> groupOffsets = adminClient.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get();

            if (groupOffsets.isEmpty()) {
                return 0L;
            }

            Map<TopicPartition, OffsetSpec> endOffsetsRequest = groupOffsets.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = adminClient.listOffsets(endOffsetsRequest).all().get();

            long totalLag = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : groupOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                long committedOffset = entry.getValue().offset();
                if (endOffsets.containsKey(tp)) {
                    long endOffset = endOffsets.get(tp).offset();
                    totalLag += Math.max(0, endOffset - committedOffset);
                }
            }
            return totalLag;
        } catch (Exception e) {
            log.warn("Failed to fetch Kafka lag for group: {}", groupId, e);
            return 0L;
        }
    }
}
