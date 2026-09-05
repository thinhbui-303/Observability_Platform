package com.thinhbui303.observability.alert.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.alert.service.AlertPersistenceService;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class SystemAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(SystemAlertConsumer.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AlertPersistenceService alertPersistenceService;

    public SystemAlertConsumer(AlertPersistenceService alertPersistenceService) {
        this.alertPersistenceService = alertPersistenceService;
    }

    // LLD 10.2: offset ACK'd only AFTER the local Postgres transaction commits.
    // persist() throws on real failures -> this method throws -> record is NOT acked -> redelivery (at-least-once).
    @KafkaListener(topics = "system-alerts", groupId = "${spring.kafka.consumer.group-id:alert-persistence-group}",
                   containerFactory = "alertKafkaListenerContainerFactory")
    public void onAlert(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) throws Exception {
        CanonicalAlertEvent event = JSON.readValue(record.value(), CanonicalAlertEvent.class);
        alertPersistenceService.persist(event);
        acknowledgment.acknowledge();
    }
}
