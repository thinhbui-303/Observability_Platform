package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.LogSearchResponse;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.service.LogSearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/logs")
public class LogSearchController {

    private final LogSearchService logSearchService;

    public LogSearchController(LogSearchService logSearchService) {
        this.logSearchService = logSearchService;
    }

    @GetMapping
    public ResponseEntity<UnifiedResponse<LogSearchResponse<Object>>> searchLogs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String spanId,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "search_after", required = false) String searchAfter
    ) throws IOException {

        LogSearchResponse<Object> searchResult = logSearchService.searchLogs(
                service, environment, level, startTime, endTime, traceId, spanId, host,
                statusCode, httpMethod, endpoint, query, page, size, searchAfter
        );

        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", searchResult));
    }
}
