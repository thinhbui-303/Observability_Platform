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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogReadIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String TEST_PREFIX = "test-audit-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");

        // Seed a known audit dataset: two rows INSIDE the DEVOPS operational scope, two OUTSIDE it.
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'CREATE_SERVICE', 'services/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'CREATE_ALERT_RULE', 'alert-rules/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'ACKNOWLEDGE_ALERT', 'alerts/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
        jdbcTemplate.update("INSERT INTO audit_logs (username, action, resource_target, ip_address, result_status, created_at) VALUES ('ops-user', 'CREATE_USER', 'users/" + TEST_PREFIX + "', '127.0.0.1', 'SUCCESS', now())");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target LIKE '" + TEST_PREFIX + "' OR resource_target LIKE 'services/" + TEST_PREFIX + "' OR resource_target LIKE 'alert-rules/" + TEST_PREFIX + "' OR resource_target LIKE 'alerts/" + TEST_PREFIX + "' OR resource_target LIKE 'users/" + TEST_PREFIX + "'");
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user", "test_developer_user");
    }

    private String jwtFor(String username) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword("password123");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(start, body.indexOf("\"", start));
    }

    @Test
    void testAuditLogs_AsDevops_ShouldOnlySeeOperationalScope() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].resourceTarget", everyItem(
                        anyOf(startsWith("services/"), startsWith("alert-rules/")))));
    }

    @Test
    void testAuditLogs_AsAdmin_ShouldSeeAll() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)));
    }

    @Test
    void testAuditLogs_AsDeveloper_ShouldReturn403() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_developer_user", "DEVELOPER");
        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + jwtFor("test_developer_user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void testAuditLogs_FilterByResourceTarget_ShouldApply() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("resourceTarget", TEST_PREFIX)
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)));
    }
}