package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.LogSearchResponse;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.service.LogSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/logs")
@Tag(name = "Log Search", description = "Endpoints for searching and viewing ingested logs")
public class LogSearchController {

    private final LogSearchService logSearchService;

    public LogSearchController(LogSearchService logSearchService) {
        this.logSearchService = logSearchService;
    }

    @GetMapping
    @Operation(
            summary = "Search logs",
            description = "Search logs with various filters. Note: Pagination can be done via `page`/`size` (up to 10k limit) OR via `search_after` for deep pagination. When searching by `traceId`, results are sorted ascending by timestamp. Yêu cầu role: ADMIN, DEVOPS, DEVELOPER, VIEWER (authenticated).",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Search successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request parameters", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Internal server error (e.g. Elasticsearch failure)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<LogSearchResponse<Object>>> searchLogs(
            @Parameter(description = "Filter by service name") @RequestParam(required = false) String service,
            @Parameter(description = "Filter by environment") @RequestParam(required = false) String environment,
            @Parameter(description = "Filter by log level") @RequestParam(required = false) String level,
            @Parameter(description = "Start of time range (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @Parameter(description = "End of time range (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @Parameter(description = "Filter by trace ID (forces ASC sorting)") @RequestParam(required = false) String traceId,
            @Parameter(description = "Filter by span ID") @RequestParam(required = false) String spanId,
            @Parameter(description = "Filter by host") @RequestParam(required = false) String host,
            @Parameter(description = "Filter by HTTP status code") @RequestParam(required = false) Integer statusCode,
            @Parameter(description = "Filter by HTTP method") @RequestParam(required = false) String httpMethod,
            @Parameter(description = "Filter by API endpoint") @RequestParam(required = false) String endpoint,
            @Parameter(description = "Full text search query on log message") @RequestParam(required = false) String query,
            @Parameter(description = "Page number (0-based) for standard pagination") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size for standard pagination") @RequestParam(required = false) Integer size,
            @Parameter(description = "Cursor for deep pagination. Use the `nextSearchAfter` value from the previous response.") @RequestParam(name = "search_after", required = false) String searchAfter
    ) throws IOException {

        LogSearchResponse<Object> searchResult = logSearchService.searchLogs(
                service, environment, level, startTime, endTime, traceId, spanId, host,
                statusCode, httpMethod, endpoint, query, page, size, searchAfter
        );

        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", searchResult));
    }
}
