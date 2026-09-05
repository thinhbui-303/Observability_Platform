package com.thinhbui303.observability.indexer.retry;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RetryPolicy {

    public static final int MAX_ATTEMPTS = 3;
    
    // Topic names based on retry count (1 -> 1s, 2 -> 5s, 3 -> 30s)
    public static final String TOPIC_RETRY_1S = "logs.retry.1s";
    public static final String TOPIC_RETRY_5S = "logs.retry.5s";
    public static final String TOPIC_RETRY_30S = "logs.retry.30s";

    private final Set<Integer> retryableStatusCodes = Set.of(429, 408, 500, 502, 503, 504);
    private final Set<String> retryableErrorTypes = Set.of(
            "es_rejected_execution_exception", 
            "timeout_exception", 
            "cluster_block_exception"
    );

    public boolean isRetryable(int statusCode, String errorType) {
        if (retryableStatusCodes.contains(statusCode)) {
            return true;
        }
        if (errorType != null) {
            for (String type : retryableErrorTypes) {
                if (errorType.contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getNextRetryTopic(int currentRetryCount) {
        return switch (currentRetryCount) {
            case 0 -> TOPIC_RETRY_1S;
            case 1 -> TOPIC_RETRY_5S;
            case 2 -> TOPIC_RETRY_30S;
            default -> null; // Exceeded max attempts
        };
    }
}
