package com.thinhbui303.observability.core.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.core.TestSeeds;
import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import com.thinhbui303.observability.core.dashboard.dto.DashboardMetricsPayload;
import com.thinhbui303.observability.core.dashboard.dto.ServiceHealthEntry;
import com.thinhbui303.observability.core.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.sockjs.client.Transport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.dashboard.metrics-tick-ms=600000")
@ActiveProfiles("test")
public class DashboardSnapshotReplayIntegrationTest {

    private static final String VIEWER = "test_dash_replay_viewer";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DashboardSnapshotCache snapshotCache;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, VIEWER, "VIEWER");
        stompClient = new WebSocketStompClient(new org.springframework.web.socket.client.standard.StandardWebSocketClient());
        // DTOs carry java.time.Instant fields; a plain ObjectMapper cannot deserialize those
        // (Missing jackson-datatype-jsr310) — give the converter a JSR-310-aware mapper.
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        converter.setObjectMapper(mapper);
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        TestSeeds.deleteTestUsers(jdbcTemplate, VIEWER);
        if (stompClient != null) {
            try { stompClient.stop(); } catch (Exception ignored) { }
        }
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private StompHeaders authHeaders() {
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + jwt());
        headers.set("heart-beat", "0,0");
        return headers;
    }

    private String jwt() {
        return Jwts.builder()
                .setSubject(VIEWER)
                .claim("roles", List.of("VIEWER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    private StompSession connect() throws Exception {
        CompletableFuture<StompSession> future = stompClient.connectAsync(
                wsUrl(), (WebSocketHttpHeaders) null, authHeaders(), new StompSessionHandlerAdapter() {});
        StompSession session = future.get(10, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isTrue();
        return session;
    }

    @Test
    void testSubscribeMetrics_ShouldReceiveSnapshotImmediately() throws Exception {
        // Put a known snapshot in the cache BEFORE subscribing — replay must push it now.
        Instant computed = Instant.parse("2026-09-06T10:00:00Z");
        
        DashboardMetricsPayload.ServiceMetrics sm = new DashboardMetricsPayload.ServiceMetrics(
            "payment-service", "payment-service", "HEALTHY", 12.5, 0.018, computed
        );
        DashboardMetricsPayload.KafkaMetrics km = new DashboardMetricsPayload.KafkaMetrics(0, 0);
        DashboardMetricsPayload metrics = new DashboardMetricsPayload(computed, List.of(sm), km);
        
        snapshotCache.update(metrics);

        BlockingQueue<DashboardMetricsPayload> received = new LinkedBlockingQueue<>();
        StompSession session = connect();
        session.subscribe("/topic/dashboard/metrics", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardMetricsPayload.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((DashboardMetricsPayload) payload);
            }
        });

        DashboardMetricsPayload got = received.poll(5, TimeUnit.SECONDS);
        assertThat(got).isNotNull();
        assertThat(got.timestamp()).isEqualTo(computed);
        assertThat(got.services()).hasSize(1);
        assertThat(got.services().get(0).serviceId()).isEqualTo("payment-service");
        assertThat(got.services().get(0).logsPerSecond()).isEqualTo(12.5);
    }
}