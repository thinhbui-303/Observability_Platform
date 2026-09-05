package com.thinhbui303.observability.indexer.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class); // We will deserialize manually or use JSON deserializer
        
        // Consumer Isolation & Constraints
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        
        // Batch configuration (LLD: 1000 items / 500ms)
        // SDD 13: Target real-time SLA < 3000ms.
        // The fetch.max.wait.ms = 500 strictly bounds the consumer's internal batch wait time.
        // (Note: End-to-end latency in local tests measures ~902ms, which accounts for the 500ms Kafka wait 
        // plus ~400ms of Testcontainers/Spring/Elasticsearch test polling overhead, well within the 3s SLA.)
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1); // Fetch immediately if max.wait is reached
        
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Value("${spring.kafka.listener.concurrency:3}")
    private int concurrency;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setBatchListener(true);
        
        // BLOCKER from LLD: Do NOT use CallerRunsPolicy with ThreadPoolExecutor. 
        // We use setConcurrency() instead to scale by partition.
        factory.setConcurrency(concurrency);
        
        // MANUAL ACK is required to enforce ACK Ordering
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // Ensure poll timeout is short enough (e.g., 500ms) to unblock the poll loop frequently
        // so it can respect the fetch.max.wait.ms correctly at low loads.
        factory.getContainerProperties().setPollTimeout(500);
        
        return factory;
    }
}
