package com.thinhbui303.observability.core.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.thinhbui303.observability.core.api.dto.LogSearchResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LogSearchService {

    private final ElasticsearchClient elasticsearchClient;

    public LogSearchService(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    public LogSearchResponse<Object> searchLogs(
            String service, String environment, String level, 
            Instant startTime, Instant endTime, String traceId, String spanId, 
            String host, Integer statusCode, String httpMethod, 
            String endpoint, String queryStr, 
            Integer page, Integer size, String searchAfter) throws IOException {

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        if (service != null) boolQuery.filter(q -> q.term(t -> t.field("serviceName").value(service)));
        if (environment != null) boolQuery.filter(q -> q.term(t -> t.field("environment").value(environment)));
        if (level != null) boolQuery.filter(q -> q.term(t -> t.field("level").value(level)));
        if (traceId != null) boolQuery.filter(q -> q.term(t -> t.field("traceId").value(traceId)));
        if (spanId != null) boolQuery.filter(q -> q.term(t -> t.field("spanId").value(spanId)));
        if (host != null) boolQuery.filter(q -> q.term(t -> t.field("host").value(host)));
        if (statusCode != null) boolQuery.filter(q -> q.term(t -> t.field("statusCode").value(statusCode)));
        if (httpMethod != null) boolQuery.filter(q -> q.term(t -> t.field("httpMethod").value(httpMethod)));
        if (endpoint != null) boolQuery.filter(q -> q.term(t -> t.field("endpoint").value(endpoint)));

        if (startTime != null || endTime != null) {
            boolQuery.filter(q -> q.range(r -> {
                r.field("timestamp");
                if (startTime != null) r.gte(co.elastic.clients.json.JsonData.of(startTime.toString()));
                if (endTime != null) r.lte(co.elastic.clients.json.JsonData.of(endTime.toString()));
                return r;
            }));
        }

        if (queryStr != null && !queryStr.isEmpty()) {
            // Add wildcards for partial matching if not already present
            String finalQueryStr = queryStr;
            if (!queryStr.contains("*") && !queryStr.contains(" AND ") && !queryStr.contains(" OR ")) {
                finalQueryStr = "*" + queryStr + "*";
            }
            final String qs = finalQueryStr;
            boolQuery.must(q -> q.queryString(qsb -> qsb.fields("message").query(qs)));
        }

        Query query = boolQuery.build()._toQuery();

        boolean isTraceMode = (traceId != null);
        SortOrder timestampSortOrder = isTraceMode ? SortOrder.Asc : SortOrder.Desc;

        int finalSize = (size != null && size > 0) ? size : 20;

        SearchRequest.Builder searchRequestBuilder = new SearchRequest.Builder()
                .index("logs-*")
                .query(query)
                .size(finalSize);
                
        // If a search query is provided, sort by relevance (score) first so best matches appear at the top
        if (queryStr != null && !queryStr.isEmpty()) {
            searchRequestBuilder.sort(s -> s.score(sc -> sc));
            searchRequestBuilder.sort(s -> s.field(f -> f.field("timestamp").order(SortOrder.Desc)));
        } else {
            searchRequestBuilder.sort(s -> s.field(f -> f.field("timestamp").order(timestampSortOrder)));
            searchRequestBuilder.sort(s -> s.field(f -> f.field("eventId").order(SortOrder.Asc))); // tiebreaker
        }

        boolean isPageMode = (searchAfter == null);
        
        if (isPageMode) {
            int finalPage = (page != null && page >= 0) ? page : 0;
            searchRequestBuilder.from(finalPage * finalSize);
        } else {
            // parse searchAfter timestamp,eventId
            String[] parts = searchAfter.split(",");
            if (parts.length == 2) {
                searchRequestBuilder.searchAfter(parts[0], parts[1]);
            } else {
                throw new IllegalArgumentException("Invalid searchAfter format. Expected timestamp,eventId");
            }
        }

        SearchResponse<Object> response = elasticsearchClient.search(searchRequestBuilder.build(), Object.class);

        LogSearchResponse<Object> searchResponse = new LogSearchResponse<>();
        
        List<Object> content = new ArrayList<>();
        List<String> lastSortValues = null;

        for (Hit<Object> hit : response.hits().hits()) {
            content.add(hit.source());
            if (hit.sort() != null) {
                lastSortValues = hit.sort().stream()
                        .map(fv -> fv != null && fv._get() != null ? fv._get().toString() : null)
                        .collect(Collectors.toList());
            }
        }
        
        searchResponse.setContent(content);
        searchResponse.setSize(finalSize);
        
        long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;
        searchResponse.setTotalElements(totalHits);

        if (isPageMode) {
            searchResponse.setPage(page != null ? page : 0);
            searchResponse.setTotalPages((int) Math.ceil((double) totalHits / finalSize));
        }
        
        if (lastSortValues != null && lastSortValues.size() == 2) {
            searchResponse.setNextSearchAfter(lastSortValues.get(0) + "," + lastSortValues.get(1));
        }

        return searchResponse;
    }
}
