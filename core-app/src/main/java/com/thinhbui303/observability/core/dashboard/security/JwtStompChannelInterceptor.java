package com.thinhbui303.observability.core.dashboard.security;

import com.thinhbui303.observability.core.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class JwtStompChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtStompChannelInterceptor.class);

    private final JwtTokenProvider tokenProvider;
    private final MessageChannel clientOutboundChannel;

    public JwtStompChannelInterceptor(@Lazy JwtTokenProvider tokenProvider,
                                      @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this.tokenProvider = tokenProvider;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() == StompCommand.CONNECT
                || accessor.getCommand() == StompCommand.STOMP) {
            String bearer = accessor.getFirstNativeHeader("Authorization");
            if (bearer == null || bearer.isBlank()) {
                log.warn("STOMP CONNECT rejected: missing Authorization header");
                return reject(accessor, "Missing JWT Authorization header on STOMP CONNECT");
            }
            String token = bearer.startsWith("Bearer ") ? bearer.substring(7) : bearer;
            if (!tokenProvider.validateToken(token)) {
                log.warn("STOMP CONNECT rejected: invalid/expired JWT");
                return reject(accessor, "Invalid or expired JWT on STOMP CONNECT");
            }
            accessor.setUser(() -> tokenProvider.getUsernameFromJWT(token));
            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("jwt", token);
            }
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            if ("/topic/dashboard/self-health".equals(destination)) {
                String token = null;
                if (accessor.getSessionAttributes() != null) {
                    token = (String) accessor.getSessionAttributes().get("jwt");
                }
                if (token == null) {
                    log.warn("STOMP SUBSCRIBE rejected: no JWT found in session for self-health");
                    return reject(accessor, "Unauthorized: missing JWT in session");
                }
                java.util.List<String> roles = tokenProvider.getRolesFromJWT(token);
                if (roles == null || !roles.contains("ADMIN")) {
                    log.warn("STOMP SUBSCRIBE rejected: user is not ADMIN for self-health");
                    return reject(accessor, "Forbidden: requires ADMIN role");
                }
            }
        }
        return message;
    }

    private Message<?> reject(StompHeaderAccessor accessor, String reason) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setSessionId(accessor.getSessionId());
        error.setMessage(reason);
        error.setLeaveMutable(true);
        clientOutboundChannel.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
        return null;
    }
}
