package com.thinhbui303.observability.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinhbui303.observability.common.ApiKeyHashUtil;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceRegistryIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String plaintextKey;

    // slug("Test Service <suffix>") == "test-service-<suffix>" == TEST_SVC (stable per class)
    private static final String TEST_SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_SVC_NAME = "Test Service " + TEST_SUFFIX;
    private static final String TEST_SVC = "test-service-" + TEST_SUFFIX;

    @BeforeEach
    void setUp() throws Exception {
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_admin_user", "ADMIN");
        TestSeeds.seedUserWithRole(jdbcTemplate, userRepository, passwordEncoder, "test_devops_user", "DEVOPS");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE resource_target = 'services/" + TEST_SVC + "'");
        jdbcTemplate.update("DELETE FROM service_api_keys WHERE service_id = ?", TEST_SVC);
        jdbcTemplate.update("DELETE FROM services WHERE id = ?", TEST_SVC);
        TestSeeds.deleteTestUsers(jdbcTemplate, "test_admin_user", "test_devops_user");
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
    void testCreateService_ShouldReturnPlaintextKeyOnce() throws Exception {
        String body = """
                {
                    "name": "%s",
                    "teamOwner": "backend-team",
                    "environment": "production"
                }
                """.formatted(TEST_SVC_NAME);
        MvcResult result = mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(TEST_SVC))
                .andExpect(jsonPath("$.data.plaintextApiKey").exists())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        int start = response.indexOf("\"plaintextApiKey\":\"") + 19;
        String key = response.substring(start, response.indexOf("\"", start));
        plaintextKey = key;
        assertTrue(key.startsWith("sk_"));
        assertEquals(12, key.substring(0, 12).length());

        // DB stores ONLY the SHA-256 hash + prefix — never the plaintext
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM service_api_keys WHERE service_id = ?", String.class, TEST_SVC);
        assertThat(storedHash).isEqualTo(ApiKeyHashUtil.hash(key));
    }

    @Test
    void testCreateService_ShouldWriteSuccessAuditRow() throws Exception {
        String body = """
                {
                    "name": "%s",
                    "teamOwner": "backend-team",
                    "environment": "production"
                }
                """.formatted(TEST_SVC_NAME);
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // BR-009 SUCCESS path: an audit row with the acting principal, action, target and IP.
        // MockMvc's MockHttpServletRequest defaults getRemoteAddr() to 127.0.0.1.
        Integer successRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CREATE_SERVICE' AND result_status = 'SUCCESS'"
                        + " AND resource_target = 'services/" + TEST_SVC + "' AND username = 'test_admin_user'"
                        + " AND ip_address = '127.0.0.1'",
                Integer.class);
        assertEquals(1, successRows);
    }

    @Test
    void testGetServices_AsDevops_ShouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void testPatchServiceStatus_AsDevops_ShouldReturn403() throws Exception {
        // Pre-create a service via ADMIN so the target exists
        String body = """
                {
                    "name": "%s",
                    "teamOwner": "backend-team",
                    "environment": "production"
                }
                """.formatted(TEST_SVC_NAME);
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwtFor("test_admin_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/services/" + TEST_SVC + "/status")
                        .header("Authorization", "Bearer " + jwtFor("test_devops_user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "DISABLED" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
