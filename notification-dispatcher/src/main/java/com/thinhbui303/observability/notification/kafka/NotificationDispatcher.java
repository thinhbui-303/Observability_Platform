package com.thinhbui303.observability.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.notification.config.ChannelConfigService;
import com.thinhbui303.observability.notification.config.ChannelConfigService.ChannelConfig;
import com.thinhbui303.observability.notification.service.AlertCooldownService;
import com.thinhbui303.observability.notification.service.NotificationSender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ChannelConfigService channels;
    private final AlertCooldownService cooldown;
    private final NotificationSender sender;

    public NotificationDispatcher(ChannelConfigService channels,
                                  AlertCooldownService cooldown,
                                  NotificationSender sender) {
        this.channels = channels;
        this.cooldown = cooldown;
        this.sender = sender;
    }

    @KafkaListener(topics = "system-alerts",
                   groupId = "${spring.kafka.consumer.group-id:notification-dispatch-group}",
                   containerFactory = "notificationKafkaListenerContainerFactory")
    public void onAlert(ConsumerRecord<String, String> record, Acknowledgment ack) {
        boolean ackNow = true;
        try {
            CanonicalAlertEvent event = JSON.readValue(record.value(), CanonicalAlertEvent.class);
            List<ChannelConfig> targets = channels.channelsFor(event.ruleId());
            if (targets.isEmpty()) {
                return; // no enabled channels for this rule -> ACK, nothing to do (channel-disabled path)
            }

            boolean shouldSend;
            try {
                shouldSend = cooldown.acquire(event);
            } catch (DataAccessException ex) {
                // RULING C / degraded mode: Redis is down. Do NOT crash, do NOT permanently
                // bypass cooldown; duplicates are temporarily accepted.
                log.warn("Redis unavailable; notification-degraded mode for alert {} (duplicates possible): {}",
                        event.alertId(), ex.getMessage());
                shouldSend = true;
            }

            if (shouldSend) {
                List<ChannelConfig> failed = sender.sendAll(event, targets);
                if (!failed.isEmpty()) {
                    cooldown.release(event); // send failed -> release cooldown so retry possible
                }
                // success -> keep cooldown key (nothing to do)
            } else {
                log.info("Alert {} suppressed (within cooldown)", event.alertId());
            }
        } catch (Exception ex) {
            log.error("Error dispatching notification for alert, leaving un-acked for redelivery", ex);
            ackNow = false;
        } finally {
            if (ackNow) {
                ack.acknowledge();
            }
        }
    }
}
