package com.thinhbui303.observability.ingestion.security;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class ApiKeyValidator {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Cacheable(cacheNames = "apiKeyCache", key = "#rawApiKey")
    public AuthenticatedServiceIdentity validate(String rawApiKey) {
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
