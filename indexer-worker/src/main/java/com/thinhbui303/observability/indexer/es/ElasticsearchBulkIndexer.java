package com.thinhbui303.observability.indexer.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class ElasticsearchBulkIndexer {

    private final ElasticsearchClient elasticsearchClient;
    private final IndexNameResolver indexNameResolver;

    public ElasticsearchBulkIndexer(ElasticsearchClient elasticsearchClient, IndexNameResolver indexNameResolver) {
        this.elasticsearchClient = elasticsearchClient;
        this.indexNameResolver = indexNameResolver;
    }

    public BulkResponse bulkIndex(List<CanonicalLogEvent> events) throws IOException {
        if (events == null || events.isEmpty()) {
            return null;
        }

        BulkRequest.Builder br = new BulkRequest.Builder();

        for (CanonicalLogEvent event : events) {
            String indexName = indexNameResolver.resolve(event);
            LogDocument document = mapToDocument(event);

            br.operations(op -> op
                    .create(c -> c
                            .index(indexName)
                            .id(event.eventId())
                            .document(document)
                    )
            );
        }

        return elasticsearchClient.bulk(br.build());
    }

    private LogDocument mapToDocument(CanonicalLogEvent event) {
        return new LogDocument(
                event.eventId(),
                event.timestamp(),
                event.serviceName(),
                event.environment(),
                event.level(),
                event.message(),
                event.traceId(),
                event.spanId(),
                event.host(),
                event.instanceId(),
                event.logger(),
                event.httpMethod(),
                event.endpoint(),
                event.statusCode(),
                event.durationMs(),
                event.metadata()
        );
    }
}
