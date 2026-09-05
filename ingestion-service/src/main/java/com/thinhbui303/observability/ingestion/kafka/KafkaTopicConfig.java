package com.thinhbui303.observability.ingestion.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic rawLogsTopic() {
        return TopicBuilder.name("raw-logs")
                .partitions(12)
                .replicas(1)
                .build();
    }

    @Bean
    public ProducerFactory<String, CanonicalLogEvent> producerFactory(KafkaProperties properties, ObjectMapper objectMapper) {
        Map<String, Object> props = properties.buildProducerProperties(null);
        DefaultKafkaProducerFactory<String, CanonicalLogEvent> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, CanonicalLogEvent> kafkaTemplate(ProducerFactory<String, CanonicalLogEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
