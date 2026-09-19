package com.thinhbui303.observability.indexer.handler;

import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.indexer.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ItemResultHandler {

    private static final Logger log = LoggerFactory.getLogger(ItemResultHandler.class);

    private final RetryPublisher retryPublisher;
    private final DlqPublisher dlqPublisher;
    private final RetryPolicy retryPolicy;

    public ItemResultHandler(RetryPublisher retryPublisher, DlqPublisher dlqPublisher, RetryPolicy retryPolicy) {
        this.retryPublisher = retryPublisher;
        this.dlqPublisher = dlqPublisher;
        this.retryPolicy = retryPolicy;
    }

    public DlqPublisher getDlqPublisher() {
        return dlqPublisher;
    }

    public RetryPublisher getRetryPublisher() {
        return retryPublisher;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public void handleBulkResult(List<BulkResponseItem> items, List<CanonicalLogEvent> originalEvents, 
                                 String originalTopic, int originalPartition, long originalOffsetBase,
                                 int currentRetryCount, long firstFailedAt) {
        
        Map<String, CanonicalLogEvent> eventMap = originalEvents.stream()
                .collect(Collectors.toMap(CanonicalLogEvent::eventId, e -> e));

        for (int i = 0; i < items.size(); i++) {
            BulkResponseItem item = items.get(i);
            CanonicalLogEvent event = eventMap.get(item.id());
            if (event == null) {
                // Try fallback to list index if ID wasn't properly mapped
                if (i < originalEvents.size()) {
                    event = originalEvents.get(i);
                } else {
                    log.error("Could not find original event for bulk item id: {}", item.id());
                    continue;
                }
            }

            int status = item.status();
            
            if (status == 201 || status == 200) {
                // Success
                continue;
            } else if (status == 409) {
                // Idempotent success (conflict)
                log.debug("Document {} already exists (409 Conflict), ignoring.", item.id());
                continue;
            }

            String errorType = item.error() != null ? item.error().type() : "unknown";
            String errorMessage = item.error() != null ? item.error().reason() : "unknown error";
            long failedAt = (firstFailedAt > 0) ? firstFailedAt : System.currentTimeMillis();

            if (retryPolicy.isRetryable(status, errorType)) {
                String nextTopic = retryPolicy.getNextRetryTopic(currentRetryCount);
                if (nextTopic != null) {
                    // Offset computation for the specific record is complex if we only have batch base offset.
                    // We'll pass the base offset, but ideally each record has its own offset. 
                    // This is sufficient for tracing.
                    retryPublisher.publish(nextTopic, event, originalTopic, originalPartition, originalOffsetBase + i,
                            currentRetryCount + 1, errorType, errorMessage, failedAt);
                } else {
                    // Exceeded max attempts, send to DLQ
                    dlqPublisher.publish(event, originalTopic, originalPartition, originalOffsetBase + i,
                            currentRetryCount, errorType, errorMessage, failedAt);
                }
            } else {
                // Non-retryable error, send directly to DLQ
                dlqPublisher.publish(event, originalTopic, originalPartition, originalOffsetBase + i,
                        currentRetryCount, errorType, errorMessage, failedAt);
            }
        }
    }
}
