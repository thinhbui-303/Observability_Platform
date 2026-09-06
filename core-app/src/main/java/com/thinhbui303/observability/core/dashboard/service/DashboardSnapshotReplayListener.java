package com.thinhbui303.observability.core.dashboard.service;

import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * SRS line 674 (auto-reconnect): on a fresh SUBSCRIBE to a dashboard topic,
 * immediately deliver that session its own copy of the last computed snapshot.
 *
 * <p>Two subtleties:
 * <ul>
 *   <li>{@code SimpMessagingTemplate} has no {@code convertAndSendToSession}; the broker
 *       filters to a single session via the {@code simpSessionId} header on a MESSAGE frame.</li>
 *   <li>The {@code SessionSubscribeEvent} for a SUBSCRIBE is published when the frame reaches
 *       {@code clientInboundChannel}, but the broker processes that channel on its own async
 *       executor — so the subscription may not be registered yet. Sending with a short deferral
 *       makes the replay land after the broker registered the subscription.</li>
 * </ul>
 */
@Component
public class DashboardSnapshotReplayListener {

    private static final String TOPIC_METRICS = "/topic/dashboard/metrics";
    private static final String TOPIC_HEALTH = "/topic/dashboard/service-health";
    private static final Duration REPLAY_DELAY = Duration.ofMillis(500);

    private final SimpMessagingTemplate messagingTemplate;
    private final DashboardSnapshotCache snapshotCache;
    private final TaskScheduler taskScheduler;

    public DashboardSnapshotReplayListener(SimpMessagingTemplate messagingTemplate,
                                           DashboardSnapshotCache snapshotCache,
                                           TaskScheduler taskScheduler) {
        this.messagingTemplate = messagingTemplate;
        this.snapshotCache = snapshotCache;
        this.taskScheduler = taskScheduler;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                event.getMessage(), StompHeaderAccessor.class);
        if (accessor == null || accessor.getDestination() == null || accessor.getSessionId() == null) {
            return;
        }
        String destination = accessor.getDestination();
        DashboardSnapshotCache.Snapshot snapshot = snapshotCache.currentSnapshot();
        if (snapshot == null) {
            return; // nothing computed yet; the periodic broadcast will deliver asap
        }
        // Session-scoped MESSAGE: the simpSessionId header makes the broker deliver only to
        // this session's own subscription on the destination (client-specific, NOT a fan-out).
        SimpMessageHeaderAccessor outbound = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        outbound.setSessionId(accessor.getSessionId());
        outbound.setLeaveMutable(true);
        Map<String, Object> headers = outbound.getMessageHeaders();

        Object payload;
        String topic;
        if (TOPIC_METRICS.equals(destination)) {
            topic = TOPIC_METRICS;
            payload = snapshot.metrics();
        } else if (TOPIC_HEALTH.equals(destination)) {
            topic = TOPIC_HEALTH;
            payload = snapshot.serviceHealth();
        } else {
            return;
        }
        Object effectivePayload = payload;
        taskScheduler.schedule(() -> messagingTemplate.convertAndSend(topic, effectivePayload, headers),
                Instant.now().plus(REPLAY_DELAY));
    }
}