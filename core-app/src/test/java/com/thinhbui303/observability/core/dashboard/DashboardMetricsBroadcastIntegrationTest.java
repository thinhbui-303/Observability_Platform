package com.thinhbui303.observability.core.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.core.TestSeeds;
import com.thinhbui303.observability.core.dashboard.cache.DashboardSnapshotCache;
import com.thinhbui303.observability.core.dashboard.dto.DashboardMetricsPayload;
import com.thinhbui303.observability.core.dashboard.service.DashboardMetricsScheduler;
import com.thinhbui303.observability.core.dashboard.service.DashboardMetricsService;
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
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class DashboardMetricsBroadcastIntegrationTest {

    private static final String VIEWER = "test_dash_metrics_viewer";
    private static final String SERVICE = "test-dash-metrics-svc";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DashboardMetricsService dashboardMetricsService;

    @Autowired
    private DashboardMetricsScheduler dashboardMetricsScheduler;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, VIEWER, "VIEWER");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment, status) VALUES (?, ?, 'test', 'production', 'ACTIVE')",
                SERVICE, SERVICE);
        stompClient = new WebSocketStompClient(new SockJsClient(List.<Transport>of(
                new WebSocketTransport(new StandardWebSocketClient()))));
        // DTOs carry java.time.Instant fields; default converter's ObjectMapper lacks the
        // JSR-310 module, so give it a JavaTimeModule-registered mapper.
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", SERVICE);
        TestSeeds.deleteTestUsers(jdbcTemplate, VIEWER);
        if (stompClient != null) {
            try { stompClient.stop(); } catch (Exception ignored) { }
        }
    }

    private String wsUrl() {
        return "http://localhost:" + port + "/ws";
    }

    private StompHeaders authHeaders() {
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + Jwts.builder()
                .setSubject(VIEWER)
                .claim("roles", List.of("VIEWER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact());
        headers.set("heart-beat", "0,0");
        return headers;
    }

    private StompSession connect() throws Exception {
        CompletableFuture<StompSession> future = stompClient.connectAsync(
                wsUrl(), (WebSocketHttpHeaders) null, authHeaders(), new StompSessionHandlerAdapter() {});
        return future.get(10, TimeUnit.SECONDS);
    }

    @Test
    void testComputeMetrics_ShouldProduceSnapshotWithoutServiceInRegistry() throws Exception {
        // The registered test service has no logs; the snapshot must still list it as UNAVAILABLE.
        DashboardSnapshotCache.Snapshot snapshot = dashboardMetricsService.computeNow();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.metrics()).isNotNull();
        assertThat(snapshot.metrics().windowSeconds()).isEqualTo(300);
        assertThat(snapshot.serviceHealth()).isNotEmpty();
        assertThat(snapshot.serviceHealth())
                .anyMatch(e -> SERVICE.equals(e.serviceId()) && "UNAVAILABLE".equals(e.status()));
    }

    @Test
    void testBroadcastMetrics_ShouldReachSubscriber_WithinExpectedLatency() throws Exception {
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

        long started = System.currentTimeMillis();
        dashboardMetricsScheduler.broadcastDashboardTick(); // compute + cache + fan-out in one go

        DashboardMetricsPayload got = received.poll(5, TimeUnit.SECONDS);
        long latencyMs = System.currentTimeMillis() - started;
        assertThat(got).isNotNull();
        assertThat(got.windowSeconds()).isEqualTo(300);
        assertThat(latencyMs).isLessThan(5000);
    }
}
