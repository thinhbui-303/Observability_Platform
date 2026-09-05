package com.thinhbui303.observability.ingestion.service;

import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.ingestion.dto.LogIngestionRequest;
import com.thinhbui303.observability.ingestion.security.AuthenticatedServiceIdentity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CanonicalEventBuilder {

    private final DataMaskingService dataMaskingService;

    public CanonicalEventBuilder(DataMaskingService dataMaskingService) {
        this.dataMaskingService = dataMaskingService;
    }

    public CanonicalLogEvent build(LogIngestionRequest request, AuthenticatedServiceIdentity identity) {
        String eventId = (request.eventId() != null && !request.eventId().isBlank()) 
                ? request.eventId() 
                : UUID.randomUUID().toString();

        return new CanonicalLogEvent(
                1, // schemaVersion
                eventId,
                request.timestamp(),
                identity.serviceId(),
                identity.environment(),
                request.level(),
                dataMaskingService.maskMessage(request.message()),
                request.traceId(),
                request.spanId(),
                request.host(),
                request.instanceId(),
                request.logger(),
                request.httpMethod(),
                request.endpoint(),
                request.statusCode(),
                request.durationMs(),
                dataMaskingService.maskMetadata(request.metadata())
        );
    }
}
