package com.thinhbui303.observability.core.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.core.dashboard.dto.DashboardAlertPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Locked decision 0.1: a THIRD independent consumer group on system-alerts, mapping
 * CanonicalAlertEvent -> DashboardAlertPayload and pushing over STOMP to /topic/dashboard/alerts.
 * This branch is PURE READ/BROADCAST — it never writes to (nor reads from) the database;
 * alert-consumer remains the single persistence writer.
 */
@Component
public class AlertBroadcastConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertBroadcastConsumer.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String TOPIC_ALERTS = "/topic/dashboard/alerts";

    private final SimpMessagingTemplate messagingTemplate;

    public AlertBroadcastConsumer(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @KafkaListener(topics = "system-alerts",
                   groupId = "${spring.kafka.consumer.group-id:dashboard-broadcast-group}",
                   containerFactory = "dashboardKafkaListenerContainerFactory")
    public void onAlert(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) throws Exception {
        CanonicalAlertEvent event = JSON.readValue(record.value(), CanonicalAlertEvent.class);
        DashboardAlertPayload payload = new DashboardAlertPayload(
                event.alertId(),
                event.ruleId(),
                event.serviceId(),
                event.severity(),
                event.condition(),
                event.triggeredAt());
        messagingTemplate.convertAndSend(TOPIC_ALERTS, payload);
        acknowledgment.acknowledge(); // at-least-once: ack only after the broadcast send succeeds
    }
}