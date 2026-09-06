package com.thinhbui303.observability.core.api.dto;

public record ServiceCreateResponse(String id, String name, String environment,
                                    String plaintextApiKey, String keyPrefix) {
}