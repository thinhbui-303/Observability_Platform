package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String TEST_SVC = "test-alert-lifecycle-svc-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_ALERT = "test-alert-" + UUID.randomUUID();
    private static final String TEST_ALERT2 = "test-alert-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_viewer_user", "VIEWER");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                TEST_SVC, "Lifecycle Test Service", "backend-team", "production");
        jdbcTemplate.update("INSERT INTO alerts (id, service_id, environment, window_start, severity, status, triggered_at, occurrence_count) VALUES (?, ?, 'production', ?, 'HIGH', 'TRIGGERED', ?, 1)",
                TEST_ALERT, TEST_SVC, Timestamp.from(Instant.now().minusSeconds(300)), Timestamp.from(Instant.now()));
        jdbcTemplate.update("INSERT INTO alerts (id, service_id, environment, window_start, severity, status, triggered_at, occurrence_count) VALUES (?, ?, 'production', ?, 'HIGH', 'OPEN', ?, 1)",
                TEST_ALERT2, TEST_SVC, Timestamp.from(Instant.now().minusSeconds(300)), Timestamp.from(Instant.now()));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target IN ('alerts/" + TEST_ALERT + "', 'alerts/" + TEST_ALERT2 + "')");
        jdbcTemplate.update("DELETE FROM alerts WHERE id LIKE 'test-alert-%'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user", "test_viewer_user");
    }

    private String jwtFor(String username) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword("password123");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(start, body.indexOf("\"", start));
    }

    @Test
    void testAlertLifecycle_ResolveBeforeAcknowledge_ShouldReturn409() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void testAlertLifecycle_ValidTransition_ShouldSetTimestampsCorrectly() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_viewer_user")))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedAt", notNullValue()))
                .andExpect(jsonPath("$.data.resolvedAt", nullValue()));

        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedAt", notNullValue()));
    }

    @Test
    void testAlertLifecycle_OpenState_IsAcknowledgedLikeTriggered() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT2 + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
    }

    @Test
    void testAlertLifecycle_ResolvedAlert_RejectsFurtherTransitions() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/acknowledge")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void testAuditLog_FailedStateTransition_ShouldPersistFailedRow() throws Exception {
        mockMvc.perform(patch("/api/v1/alerts/" + TEST_ALERT + "/resolve")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isConflict());

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_target = 'alerts/" + TEST_ALERT +
                        "' AND result_status = 'FAILED'", Integer.class);
        Integer success = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_target = 'alerts/" + TEST_ALERT +
                        "' AND result_status = 'SUCCESS'", Integer.class);
        assertEquals(1, failed, "FAILED audit row must be persisted despite the 409 (decision 1)");
        assertEquals(0, success, "no SUCCESS audit row may exist for a rejected transition");
    }

    @Test
    void testGetAlerts_AsViewer_ShouldReturnAll() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + jwtFor("test_viewer_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
