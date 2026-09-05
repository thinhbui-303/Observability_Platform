package com.thinhbui303.observability.ingestion.security;

public record AuthenticatedServiceIdentity(
        String serviceId,
        String environment
) {
}
