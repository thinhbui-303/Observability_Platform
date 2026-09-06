package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.domain.UserEntity;
import com.thinhbui303.observability.core.repository.UserRepository;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.thinhbui303.observability.core.repository.RoleRepository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import org.awaitility.Awaitility;

@SpringBootTest(classes = CoreAppApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class CoreAppIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @BeforeEach
    void setUp() throws Exception {
        if (userRepository.findByUsername("test_admin_user").isEmpty()) {
            UserEntity admin = new UserEntity();
            admin.setUsername("test_admin_user");
            admin.setPasswordHash(passwordEncoder.encode("password123"));
            UserEntity savedUser = userRepository.save(admin);
            
            jdbcTemplate.execute("INSERT INTO user_roles (user_id, role_id) VALUES (" + savedUser.getId() + ", (SELECT id FROM roles WHERE name = 'ADMIN'))");
        }
        if (userRepository.findByUsername("test_viewer_user").isEmpty()) {
            UserEntity viewer = new UserEntity();
            viewer.setUsername("test_viewer_user");
            viewer.setPasswordHash(passwordEncoder.encode("password123"));
            UserEntity savedViewer = userRepository.save(viewer);
            jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('VIEWER') ON CONFLICT (name) DO NOTHING");
            jdbcTemplate.execute("INSERT INTO user_roles (user_id, role_id) VALUES (" + savedViewer.getId() + ", (SELECT id FROM roles WHERE name = 'VIEWER'))");
        }
        
        // Seed some Elasticsearch logs for testing
        // Do not delete logs-* here to avoid destroying other test/real data
        
        String indexName = "logs-" + Instant.now().toString().substring(0, 10).replace("-", ".");
        String uniqueTraceId1 = "trace-100-" + UUID.randomUUID().toString();
        String uniqueTraceId2 = "trace-200-" + UUID.randomUUID().toString();
        
        System.setProperty("TEST_TRACE_ID_1", uniqueTraceId1);
        elasticsearchClient.index(IndexRequest.of(i -> i
                .index(indexName)
                .id(UUID.randomUUID().toString())
                .document(Map.of(
                        "eventId", "evt-001",
                        "serviceName", "payment-service",
                        "traceId", uniqueTraceId1,
                        "level", "ERROR",
                        "timestamp", Instant.now().minusSeconds(10).toString()
                ))
        ));

        // Document 2
        elasticsearchClient.index(IndexRequest.of(i -> i
                .index(indexName)
                .id(UUID.randomUUID().toString())
                .document(Map.of(
                        "eventId", "evt-002",
                        "serviceName", "payment-service",
                        "traceId", uniqueTraceId1,
                        "level", "INFO",
                        "timestamp", Instant.now().minusSeconds(5).toString()
                ))
        ));

        // Document 3
        elasticsearchClient.index(IndexRequest.of(i -> i
                .index(indexName)
                .id(UUID.randomUUID().toString())
                .document(Map.of(
                        "eventId", "evt-003",
                        "serviceName", "user-service",
                        "traceId", uniqueTraceId2,
                        "level", "ERROR",
                        "timestamp", Instant.now().toString()
                ))
        ));
        
        // Wait for ES to refresh the index so documents are searchable
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            return elasticsearchClient.search(s -> s
                    .index("logs-*")
                    .query(q -> q.term(t -> t.field("traceId").value(uniqueTraceId1))), Object.class)
                    .hits().total().value() >= 2;
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username = 'test_admin_user')");
        jdbcTemplate.execute("DELETE FROM users WHERE username = 'test_admin_user'");
        jdbcTemplate.execute("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ('test_viewer_user'))");
        jdbcTemplate.execute("DELETE FROM users WHERE username = 'test_viewer_user'");
        
        String traceId1 = System.getProperty("TEST_TRACE_ID_1");
        if (traceId1 != null) {
            try {
                elasticsearchClient.deleteByQuery(d -> d
                        .index("logs-*")
                        .query(q -> q.term(t -> t.field("traceId").value(traceId1)))
                );
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private String getValidJwt() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("test_admin_user");
        loginRequest.setPassword("password123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String responseStr = result.getResponse().getContentAsString();
        int start = responseStr.indexOf("\"accessToken\":\"") + 15;
        int end = responseStr.indexOf("\"", start);
        return responseStr.substring(start, end);
    }

    @Test
    void testLogin_ValidCredentials_ShouldReturnJwt() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("test_admin_user");
        loginRequest.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    void testLogin_InvalidCredentials_ShouldReturn401() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("test_admin_user");
        loginRequest.setPassword("wrongpassword");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void testSearchLogs_WithoutToken_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/logs?service=payment-service"))
                .andExpect(status().isUnauthorized()); // Explicitly check for 401 Unauthorized
    }

    @Test
    void testSearchLogs_WithToken_ShouldReturnSuccess() throws Exception {
        String jwt = getValidJwt();
        mockMvc.perform(get("/api/v1/logs?service=payment-service")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void testSearchLogs_PageMode_ShouldReturnCorrectPageAndFilters() throws Exception {
        String jwt = getValidJwt();
        String traceId = System.getProperty("TEST_TRACE_ID_1");
        mockMvc.perform(get("/api/v1/logs")
                        .param("service", "payment-service")
                        .param("level", "ERROR")
                        .param("traceId", traceId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].eventId").value("evt-001"));
    }

    @Test
    void testSearchLogs_ByTraceId_ShouldReturnOrderedByTimestamp() throws Exception {
        String jwt = getValidJwt();
        String traceId = System.getProperty("TEST_TRACE_ID_1");
        mockMvc.perform(get("/api/v1/logs")
                        .param("traceId", traceId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].eventId").value("evt-001")) // Ascending timestamp order
                .andExpect(jsonPath("$.data.content[1].eventId").value("evt-002"));
    }

    @Test
    void testSearchLogs_SearchAfterMode_ShouldPaginateDeepWithoutFromSize() throws Exception {
        String jwt = getValidJwt();
        String traceId = System.getProperty("TEST_TRACE_ID_1");
        // First page
        MvcResult result1 = mockMvc.perform(get("/api/v1/logs")
                        .param("traceId", traceId)
                        .param("size", "1")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].eventId").value("evt-001")) // First page (ASC)
                .andReturn();

        String responseStr = result1.getResponse().getContentAsString();
        // Extract nextSearchAfter
        int start = responseStr.indexOf("\"nextSearchAfter\":\"") + 19;
        int end = responseStr.indexOf("\"", start);
        String searchAfter = responseStr.substring(start, end);

        // Second page
        mockMvc.perform(get("/api/v1/logs")
                        .param("traceId", traceId)
                        .param("size", "1")
                        .param("search_after", searchAfter)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].eventId").value("evt-002")); // Second page (ASC)
    }

    @Test
    void testSearchLogs_InvalidTimeRangeParam_ShouldReturnValidationError() throws Exception {
        String jwt = getValidJwt();
        mockMvc.perform(get("/api/v1/logs")
                        .param("startTime", "invalid-time")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void testSearchLogs_AsViewer_ShouldReturn200() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("test_viewer_user");
        loginRequest.setPassword("password123");

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();
        String jwt = login.getResponse().getContentAsString();
        int start = jwt.indexOf("\"accessToken\":\"") + 15;
        int end = jwt.indexOf("\"", start);
        String token = jwt.substring(start, end);

        mockMvc.perform(get("/api/v1/logs?service=payment-service")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
