package com.thinhbui303.observability.notification.service;

import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.notification.client.WebhookClient;
import com.thinhbui303.observability.notification.config.ChannelConfigService.ChannelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final WebhookClient webhookClient;

    public NotificationSender(WebhookClient webhookClient) {
        this.webhookClient = webhookClient;
    }

    /**
     * Sends to every channel; a failure on one channel must NOT abort the others.
     * Returns the channels that failed, so the caller can release the cooldown lock.
     */
    public List<ChannelConfig> sendAll(CanonicalAlertEvent event, List<ChannelConfig> targets) {
        List<ChannelConfig> failed = new ArrayList<>();
        for (ChannelConfig channel : targets) {
            try {
                webhookClient.send(channel, event);
            } catch (RestClientException ex) {
                // Covers connect/read timeouts (ResourceAccessException) AND 4xx/5xx status
                // errors (HttpClientErrorException/HttpServerErrorException).
                log.error("Webhook delivery failed for alert {} to [{}] {}", event.alertId(),
                        channel.type(), channel.target(), ex);
                failed.add(channel);
            }
        }
        return failed;
    }
}
