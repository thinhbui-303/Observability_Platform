package com.thinhbui303.observability.ingestion.controller;

import com.thinhbui303.observability.common.ApiResponse;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.ingestion.dto.LogBatchIngestionRequest;
import com.thinhbui303.observability.ingestion.dto.LogIngestionRequest;
import com.thinhbui303.observability.ingestion.kafka.KafkaLogProducer;
import com.thinhbui303.observability.ingestion.ratelimit.TokenBucketRateLimiter;
import com.thinhbui303.observability.ingestion.security.AuthenticatedServiceIdentity;
import com.thinhbui303.observability.ingestion.service.CanonicalEventBuilder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/v1/telemetry/logs")
public class LogIngestionController {

    private final CanonicalEventBuilder eventBuilder;
    private final KafkaLogProducer kafkaProducer;
    private final TokenBucketRateLimiter rateLimiter;

    public LogIngestionController(CanonicalEventBuilder eventBuilder,
                                  KafkaLogProducer kafkaProducer,
                                  TokenBucketRateLimiter rateLimiter) {
        this.eventBuilder = eventBuilder;
        this.kafkaProducer = kafkaProducer;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<String>> ingestSingleLog(
            @Valid @RequestBody LogIngestionRequest request,
            @RequestAttribute("AuthenticatedServiceIdentity") AuthenticatedServiceIdentity identity)
            throws ExecutionException, InterruptedException, TimeoutException {

        rateLimiter.checkAllowed(identity.serviceId(), 1200, 1000);

        CanonicalLogEvent event = eventBuilder.build(request, identity);
        kafkaProducer.send(event);

        ApiResponse<String> response = new ApiResponse<>(
                "ACCEPTED",
                "Log event accepted",
                event.eventId(),
                event.timestamp()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<List<String>>> ingestBatchLogs(
            @Valid @RequestBody LogBatchIngestionRequest request,
            @RequestAttribute("AuthenticatedServiceIdentity") AuthenticatedServiceIdentity identity)
            throws ExecutionException, InterruptedException, TimeoutException {

        rateLimiter.checkAllowed(identity.serviceId(), 1200, 1000);

        List<String> eventIds = new ArrayList<>();
        for (LogIngestionRequest logReq : request.logs()) {
            CanonicalLogEvent event = eventBuilder.build(logReq, identity);
            kafkaProducer.send(event);
            eventIds.add(event.eventId());
        }

        ApiResponse<List<String>> response = new ApiResponse<>(
                "ACCEPTED",
                "Batch log events accepted",
                eventIds,
                eventIds.isEmpty() ? null : request.logs().get(0).timestamp()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
