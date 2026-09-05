package com.thinhbui303.observability.notification.client;

import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.notification.config.ChannelConfigService.ChannelConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class WebhookClient {

    private final RestTemplate rest;

    public WebhookClient(RestTemplate rest) {
        this.rest = rest;
    }

    public void send(ChannelConfig channel, CanonicalAlertEvent event) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", buildText(channel, event));
        // Throws on 4xx/5xx/timeout; caller (NotificationSender) records failure per channel.
        rest.postForEntity(channel.target(), body, String.class);
    }

    private String buildText(ChannelConfig channel, CanonicalAlertEvent event) {
        return String.format("[%s/%s] alert %s on %s rule=%s severity=%s status=%s",
                channel.type(), event.environment(), event.alertId(), event.serviceId(),
                event.ruleId(), event.severity(), event.status());
    }
}
