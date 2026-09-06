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

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertRuleManagementIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String TEST_SVC = "test-alert-svc-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_RULE = "test-alert-rule-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_developer_user", "DEVELOPER");
        jdbcTemplate.update("INSERT INTO services (id, name, team_owner, environment) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                TEST_SVC, "Alert Test Service", "backend-team", "production");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM alert_rule_channels WHERE alert_rule_id IN (SELECT id FROM alert_rules WHERE service_id = ?)", TEST_SVC);
        jdbcTemplate.update("DELETE FROM alert_rules WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target LIKE 'alert-rules/%'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
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
    void testCreateAlertRule_AsDeveloper_ShouldReturn403() throws Exception {
        String body = """
                {
                    "ruleName": "es-spike",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "SLACK", "target": "#ops-backend" } ]
                }
                """.formatted(TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_developer_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void testCreateAlertRule_AsAdmin_ShouldReturnSuccess_WithChannelSaved() throws Exception {
        String body = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "TELEGRAM", "target": "@ops-bot", "enabled": true } ]
                }
                """.formatted(TEST_RULE, TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.notificationChannels", hasSize(1)));

        Integer channelCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alert_rule_channels c JOIN alert_rules r ON r.id = c.alert_rule_id WHERE r.rule_name = ?",
                Integer.class, TEST_RULE);
        assertEquals(1, channelCount);
    }

    @Test
    void testPatternMatchRule_MissingConditionValue_ShouldReturnValidationError() throws Exception {
        String body = """
                {
                    "ruleName": "pm-missing-cond",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "PATTERN_MATCH",
                    "severity": "HIGH"
                }
                """.formatted(TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CREATE_ALERT_RULE' AND result_status = 'FAILED'",
                Integer.class);
        assertEquals(1, failed);
    }

    @Test
    void testPatternMatchRule_WithConditionValue_ShouldDefaultThresholdWindow() throws Exception {
        String body = """
                {
                    "ruleName": "pm-ok",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "PATTERN_MATCH",
                    "conditionValue": "OutOfMemory",
                    "severity": "MEDIUM"
                }
                """.formatted(TEST_SVC);
        mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.thresholdValue").value(1))
                .andExpect(jsonPath("$.data.windowSeconds").value(60));
    }

    @Test
    void testUpdateRule_ShouldReplaceChannels() throws Exception {
        String created = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 5,
                    "windowSeconds": 60,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "SLACK", "target": "#a" } ]
                }
                """.formatted(TEST_RULE, TEST_SVC);
        MvcResult c = mockMvc.perform(post("/api/v1/alert-rules")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(created))
                .andExpect(status().isOk())
                .andReturn();
        String cBody = c.getResponse().getContentAsString();
        int idStart = cBody.indexOf("\"id\":") + 5;
        String ruleId = cBody.substring(idStart, cBody.indexOf(",", idStart));

        String updated = """
                {
                    "ruleName": "%s",
                    "serviceId": "%s",
                    "environment": "production",
                    "conditionType": "ERROR_SPIKE",
                    "thresholdValue": 9,
                    "windowSeconds": 120,
                    "severity": "HIGH",
                    "notificationChannels": [ { "channelType": "WEBHOOK", "target": "https://example.test/hook" },
                                               { "channelType": "SLACK", "target": "#b" } ]
                }
                """.formatted(TEST_RULE, TEST_SVC);
        mockMvc.perform(put("/api/v1/alert-rules/" + ruleId)
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thresholdValue").value(9))
                .andExpect(jsonPath("$.data.notificationChannels", hasSize(2)));
    }
}
