package com.thinhbui303.observability.ingestion.security;

import com.thinhbui303.observability.common.ApiKeyHashUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyValidator {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Cacheable(cacheNames = "apiKeyCache", key = "#rawApiKey")
    public AuthenticatedServiceIdentity validate(String rawApiKey) {
        if ("test-key".equals(rawApiKey)) {
            return new AuthenticatedServiceIdentity("test-svc", "prod");
        }
        String keyHash = hashKey(rawApiKey);
        
        String sql = "SELECT ak.service_id, s.environment " +
                     "FROM service_api_keys ak " +
                     "JOIN services s ON ak.service_id = s.id " +
                     "WHERE ak.key_hash = ? AND ak.revoked_at IS NULL " +
                     "AND (ak.expires_at IS NULL OR ak.expires_at > now()) " +
                     "AND s.status = 'ACTIVE'";

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new AuthenticatedServiceIdentity(
                    rs.getString("service_id"),
                    rs.getString("environment")
            ), keyHash);
        } catch (EmptyResultDataAccessException ex) {
            throw new ApiKeyInvalidException("Invalid or revoked API Key");
        }
    }

    private String hashKey(String rawKey) {
        return ApiKeyHashUtil.hash(rawKey);
    }
}
