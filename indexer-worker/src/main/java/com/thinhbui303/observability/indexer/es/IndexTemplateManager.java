package com.thinhbui303.observability.indexer.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.PutComponentTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.json.JsonData;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;

@Component
public class IndexTemplateManager {

    private static final Logger log = LoggerFactory.getLogger(IndexTemplateManager.class);
    private final ElasticsearchClient elasticsearchClient;

    public IndexTemplateManager(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    @PostConstruct
    public void initTemplates() {
        try {
            log.info("Initializing Elasticsearch templates for logs...");
            
            // 1. Component template with mappings
            String mappingJson = """
                    {
                      "properties": {
                        "eventId": { "type": "keyword" },
                        "timestamp": { "type": "date" },
                        "serviceName": { "type": "keyword" },
                        "environment": { "type": "keyword" },
                        "level": { "type": "keyword" },
                        "message": { "type": "text", "analyzer": "standard" },
                        "traceId": { "type": "keyword" },
                        "spanId": { "type": "keyword" },
                        "host": { "type": "keyword" },
                        "instanceId": { "type": "keyword" },
                        "logger": { "type": "keyword" },
                        "httpMethod": { "type": "keyword" },
                        "endpoint": { "type": "keyword" },
                        "statusCode": { "type": "integer" },
                        "durationMs": { "type": "long" },
                        "metadata": { "type": "flattened" }
                      }
                    }
                    """;

            elasticsearchClient.cluster().putComponentTemplate(c -> c
                    .name("obs-logs-mappings")
                    .template(t -> t.mappings(m -> m.withJson(new StringReader(mappingJson))))
            );

            // 2. Index template matching logs-*
            elasticsearchClient.indices().putIndexTemplate(i -> i
                    .name("obs-logs-template")
                    .indexPatterns("logs-*")
                    .composedOf("obs-logs-mappings")
                    .priority(200) // Give it higher priority than default ES templates (which is 100)
            );

            log.info("Elasticsearch templates initialized successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize Elasticsearch templates", e);
        }
    }
}
