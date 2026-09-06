package com.thinhbui303.observability.core.api.dto;

public record ServiceResponse(String id, String name, String teamOwner,
                              String environment, String status, String createdAt) {
}