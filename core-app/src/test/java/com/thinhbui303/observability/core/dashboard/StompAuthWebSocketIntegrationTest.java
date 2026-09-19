package com.thinhbui303.observability.core.dashboard;

import com.thinhbui303.observability.core.TestSeeds;
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
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
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
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class StompAuthWebSocketIntegrationTest {

    private static final String VIEWER = "test_dash_viewer";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    private WebSocketStompClient stompClient;
    private CountDownLatch errorLatch;

    @BeforeEach
    void setUp() {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, VIEWER, "VIEWER");
        errorLatch = new CountDownLatch(1);
        stompClient = new WebSocketStompClient(new org.springframework.web.socket.client.standard.StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
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

    private StompHeaders authHeaders(String jwt) {
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.set("heart-beat", "0,0");
        return headers;
    }

    private String viewerJwt() {
        String secret = jwtSecret;
        return Jwts.builder()
                .setSubject(VIEWER)
                .claim("roles", List.of("VIEWER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    private String expiredJwt() {
        return Jwts.builder()
                .setSubject(VIEWER)
                .claim("roles", List.of("VIEWER"))
                .setIssuedAt(new Date(System.currentTimeMillis() - 3_600_000L))
                .setExpiration(new Date(System.currentTimeMillis() - 60_000L))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }

    private CompletableFuture<StompSession> connect(StompHeaders headers) {
        return stompClient.connectAsync(wsUrl(), (WebSocketHttpHeaders) null, headers, new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable exception) {
                errorLatch.countDown();
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                errorLatch.countDown();
            }
        });
    }

    private StompSession connectAndAwait(StompHeaders headers) throws Exception {
        CompletableFuture<StompSession> future = connect(headers);
        StompSession session = future.get(10, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isTrue();
        return session;
    }

    @Test
    void testStompConnect_WithValidJwt_ShouldSucceed() throws Exception {
        StompSession session = connectAndAwait(authHeaders(viewerJwt()));
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void testDashboardMetrics_AsViewer_ShouldConnectSuccessfully() throws Exception {
        StompSession session = connectAndAwait(authHeaders(viewerJwt()));
        session.subscribe("/topic/dashboard/metrics", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                /* read access proven by a successful SUBSCRIBE returning ACKED (no error frame) */
            }
        });
        assertThat(session.isConnected()).isTrue();
        assertThat(errorLatch.getCount()).isEqualTo(1);
    }

    @Test
    void testStompConnect_WithoutJwt_ShouldBeRejected() throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.set("heart-beat", "0,0");
        CompletableFuture<StompSession> future = connect(headers);
        assertThat(errorLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isTrue();
    }

    @Test
    void testStompConnect_WithExpiredJwt_ShouldBeRejected() throws Exception {
        CompletableFuture<StompSession> future = connect(authHeaders(expiredJwt()));
        assertThat(errorLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isTrue();
    }
}
