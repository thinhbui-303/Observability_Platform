package com.thinhbui303.observability.core.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thinhbui303.observability.common.CanonicalAlertEvent;
import com.thinhbui303.observability.core.TestSeeds;
import com.thinhbui303.observability.core.dashboard.dto.DashboardAlertPayload;
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
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AlertBroadcastIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String VIEWER = "test_dash_alert_viewer";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;
    // GENERIC BOUND: core-app has no custom KafkaTemplate bean (controller: rg over core-app
    // shows zero producer/consumer code), so Boot's auto-config bean KafkaTemplate<Object,Object>
    // is the only one. An injection point typed KafkaTemplate<String,Object> fails generic
    // matching against it (generics are invariant) -> NoSuchBeanDefinitionException. The test
    // sends a String key / String JSON value, which Object,Object covers. (Controller pre-fix;
    // the plan's Step 2 block originally said <String,Object>.)

    @Value("${jwt.secret}")
    private String jwtSecret;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, VIEWER, "VIEWER");
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
    void testNewAlert_ShouldBroadcastToSubscribers_WithinExpectedLatency() throws Exception {
        BlockingQueue<DashboardAlertPayload> received = new LinkedBlockingQueue<>();
        StompSession session = connect();
        session.subscribe("/topic/dashboard/alerts", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardAlertPayload.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((DashboardAlertPayload) payload);
            }
        });

        String alertId = "tda-" + UUID.randomUUID().toString().replace("-", "");
            long started = System.currentTimeMillis();
            kafkaTemplate.send("system-alerts", alertId,
                    JSON.writeValueAsString(new CanonicalAlertEvent(
                            alertId, 999L, "test-dash-svc", "production", "CRITICAL", "TRIGGERED",
                            "ERROR_SPIKE: burst", 300, Instant.parse("2026-09-06T10:00:00Z"),
                            5, Instant.now(), null, null, "test-log-trace", List.of("SLACK"))))
                    .get();

            DashboardAlertPayload got = received.poll(10, TimeUnit.SECONDS);
            long latencyMs = System.currentTimeMillis() - started;

            assertThat(got).isNotNull();
            assertThat(got.alertId()).isEqualTo(alertId);
            assertThat(got.severity()).isEqualTo("CRITICAL");
            assertThat(got.description()).isEqualTo("ERROR_SPIKE: burst");
            assertThat(got.ruleId()).isEqualTo(999L);
            assertThat(latencyMs).isLessThan(5000);
    }

    @Test
    void testAlertBroadcastConsumer_ShouldNotWriteToDatabase() throws Exception {
        String alertId = "tda-nodw-" + UUID.randomUUID().toString().replace("-", "");
        kafkaTemplate.send("system-alerts", alertId,
                JSON.writeValueAsString(new CanonicalAlertEvent(
                        alertId, 999L, "test-dash-svc", "production", "CRITICAL", "TRIGGERED",
                        "ERROR_SPIKE: burst", 300, Instant.parse("2026-09-06T10:05:00Z"),
                        5, Instant.now(), null, null, "test-log-trace-2", List.of("SLACK"))))
                .get();

        // Dwell long enough for the consumer to (potentially) act, then assert no DB row.
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            // Nothing to assert mid-loop for a pure-broadcast consumer; poll then assert.
            Thread.sleep(150);
        }
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alerts WHERE id = ?", Integer.class, alertId);
        assertThat(n).isZero();
    }
}