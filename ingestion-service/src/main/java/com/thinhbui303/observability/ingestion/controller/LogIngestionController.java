package com.thinhbui303.observability.ingestion.controller;

import com.thinhbui303.observability.common.ApiResponse;
import com.thinhbui303.observability.common.CanonicalLogEvent;
import com.thinhbui303.observability.ingestion.dto.LogBatchIngestionRequest;
import com.thinhbui303.observability.ingestion.dto.LogIngestionRequest;
import com.thinhbui303.observability.ingestion.kafka.KafkaLogProducer;
import com.thinhbui303.observability.ingestion.ratelimit.TokenBucketRateLimiter;
import com.thinhbui303.observability.ingestion.security.AuthenticatedServiceIdentity;
import com.thinhbui303.observability.ingestion.service.CanonicalEventBuilder;
import com.thinhbui303.observability.ingestion.service.MetricsPublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Log Ingestion", description = "Endpoints for ingesting logs from registered services")
public class LogIngestionController {

    private final CanonicalEventBuilder eventBuilder;
    private final KafkaLogProducer kafkaProducer;
    private final TokenBucketRateLimiter rateLimiter;
    private final MetricsPublisherService metricsPublisher;

    public LogIngestionController(CanonicalEventBuilder eventBuilder,
                                  KafkaLogProducer kafkaProducer,
                                  TokenBucketRateLimiter rateLimiter,
                                  MetricsPublisherService metricsPublisher) {
        this.eventBuilder = eventBuilder;
        this.kafkaProducer = kafkaProducer;
        this.rateLimiter = rateLimiter;
        this.metricsPublisher = metricsPublisher;
    }

    @PostMapping
    @Operation(
            summary = "Ingest a single log event",
            description = "Accepts a single log event. Duplicate `eventId`s are accepted (202) but not forwarded to Kafka. Sensitive data in specific fields may be masked.",
            security = @SecurityRequirement(name = "X-API-Key")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Log event accepted for processing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ValidationErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing, invalid, or revoked API Key", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded for this service", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Message broker unavailable", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<String>> ingestSingleLog(
            @Valid @RequestBody LogIngestionRequest request,
            @RequestAttribute("AuthenticatedServiceIdentity") AuthenticatedServiceIdentity identity)
            throws ExecutionException, InterruptedException, TimeoutException {

        rateLimiter.checkAllowed(identity.serviceId(), 1200, 1000);

        CanonicalLogEvent event = eventBuilder.build(request, identity);
        kafkaProducer.send(event);
        metricsPublisher.publishMetrics(event, identity.serviceId());

        ApiResponse<String> response = new ApiResponse<>(
                "ACCEPTED",
                "Log event accepted",
                event.eventId(),
                event.timestamp()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/batch")
    @Operation(
            summary = "Ingest a batch of log events",
            description = "Accepts multiple log events in a single request. Rate limit applies per request, not per event inside the batch.",
            security = @SecurityRequirement(name = "X-API-Key")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Batch log events accepted for processing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ValidationErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing, invalid, or revoked API Key", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded for this service", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Message broker unavailable", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<List<String>>> ingestBatchLogs(
            @Valid @RequestBody LogBatchIngestionRequest request,
            @RequestAttribute("AuthenticatedServiceIdentity") AuthenticatedServiceIdentity identity)
            throws ExecutionException, InterruptedException, TimeoutException {
        return processBatchInternal(request.logs(), identity);
    }

    @PostMapping("/batch/raw")
    @Operation(
            summary = "Ingest a raw JSON array of log events (Agent-friendly)",
            description = "Accepts multiple log events as a raw JSON array. Used by log shippers like Fluent Bit. Rate limit applies per request, not per event inside the batch.",
            security = @SecurityRequirement(name = "X-API-Key")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Batch log events accepted for processing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ValidationErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing, invalid, or revoked API Key", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded for this service", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Message broker unavailable", content = @Content(schema = @Schema(implementation = com.thinhbui303.observability.common.ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<List<String>>> ingestRawBatchLogs(
            @Valid @RequestBody List<LogIngestionRequest> request,
            @RequestAttribute("AuthenticatedServiceIdentity") AuthenticatedServiceIdentity identity)
            throws ExecutionException, InterruptedException, TimeoutException {
        return processBatchInternal(request, identity);
    }

    private ResponseEntity<ApiResponse<List<String>>> processBatchInternal(
            List<LogIngestionRequest> logs,
            AuthenticatedServiceIdentity identity)
            throws ExecutionException, InterruptedException, TimeoutException {

        rateLimiter.checkAllowed(identity.serviceId(), 1200, 1000);

        List<String> eventIds = new ArrayList<>();
        List<CanonicalLogEvent> events = new ArrayList<>();
        
        for (LogIngestionRequest logReq : logs) {
            CanonicalLogEvent event = eventBuilder.build(logReq, identity);
            kafkaProducer.send(event);
            eventIds.add(event.eventId());
            events.add(event);
        }
        
        metricsPublisher.publishBatchMetrics(events, identity.serviceId());

        ApiResponse<List<String>> response = new ApiResponse<>(
                "ACCEPTED",
                "Batch log events accepted",
                eventIds,
                eventIds.isEmpty() ? null : logs.get(0).timestamp()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
