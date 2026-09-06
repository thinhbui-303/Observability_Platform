package com.thinhbui303.observability.core.service;

import java.security.SecureRandom;
import java.util.Base64;

public final class ApiKeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}