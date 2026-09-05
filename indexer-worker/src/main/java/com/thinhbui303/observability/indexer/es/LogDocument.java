package com.thinhbui303.observability.indexer.es;

import java.time.Instant;
import java.util.Map;

/**
 * Pure POJO representation of the Elasticsearch document.
 * We don't use @Document(indexName=...) here because the index name
 * is dynamically resolved based on the event's timestamp (UTC) per document.
 */
public record LogDocument(
        String eventId,
        Instant timestamp,
        String serviceName,
        String environment,
        String level,
        String message,
        String traceId,
        String spanId,
        String host,
        String instanceId,
        String logger,
        String httpMethod,
        String endpoint,
        Integer statusCode,
        Long durationMs,
        Map<String, Object> metadata
) {
}
