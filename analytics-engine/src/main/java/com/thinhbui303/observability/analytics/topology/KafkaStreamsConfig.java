package com.thinhbui303.observability.analytics.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    public KafkaStreamsConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-engine");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class.getName());
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 4);

        // Wire CanonicalLogEventTimestampExtractor for event-time processing
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG,
                CanonicalLogEventTimestampExtractor.class.getName());

        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public Serde<CanonicalLogEvent> canonicalLogEventSerde() {
        JsonSerializer<CanonicalLogEvent> serializer = new JsonSerializer<>(objectMapper);
        JsonDeserializer<CanonicalLogEvent> deserializer = new JsonDeserializer<>(CanonicalLogEvent.class, objectMapper);
        deserializer.addTrustedPackages("*");
        return Serdes.serdeFrom(serializer, deserializer);
    }

    @Bean
    public Serde<CanonicalAlertEvent> canonicalAlertEventSerde() {
        JsonSerializer<CanonicalAlertEvent> serializer = new JsonSerializer<>(objectMapper);
        JsonDeserializer<CanonicalAlertEvent> deserializer = new JsonDeserializer<>(CanonicalAlertEvent.class, objectMapper);
        deserializer.addTrustedPackages("*");
        return Serdes.serdeFrom(serializer, deserializer);
    }

    @Bean
    public NewTopic systemAlertsTopic() {
        return TopicBuilder.name("system-alerts")
                .partitions(6)
                .replicas(1)
                .build();
    }
}
