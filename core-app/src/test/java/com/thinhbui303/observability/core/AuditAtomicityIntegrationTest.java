package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.core.api.dto.LoginRequest;
import com.thinhbui303.observability.core.domain.AlertRuleEntity;
import com.thinhbui303.observability.core.repository.AlertRuleRepository;
import com.thinhbui303.observability.core.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditAtomicityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @SpyBean
    private AlertRuleRepository alertRuleRepository;

    private static final String TEST_SVC = "test-atomic-svc-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_RULE = "test-atomic-rule-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                TEST_SVC, "Atomic Test Service", "backend-team", "production");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target LIKE 'alerts/%' OR resource_target LIKE 'services/%' OR resource_target LIKE 'alert-rules/%' OR resource_target LIKE 'users/%'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user");
    }

    private String jwtFor() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("test_admin_user");
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
    void testAuditLog_WrittenAtomicallyWithMutation_ShouldRollbackTogetherOnFailure() throws Exception {
        doThrow(new DataAccessResourceFailureException("simulated infra failure")).when(alertRuleRepository).save(any(AlertRuleEntity.class));

        String body = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH"
                }
                """.formatted(TEST_RULE, TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        Integer ruleRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alert_rules WHERE rule_name = ?", Integer.class, TEST_RULE);
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CREATE_ALERT_RULE' AND result_status = 'SUCCESS'",
                Integer.class);
        assertEquals(0, ruleRows, "main row must roll back");
        assertEquals(0, auditRows, "success audit row must roll back together with the mutation");
    }
}
