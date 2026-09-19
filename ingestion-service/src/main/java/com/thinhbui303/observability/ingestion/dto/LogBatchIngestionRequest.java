package com.thinhbui303.observability.ingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Request payload for ingesting a batch of log events")
public record LogBatchIngestionRequest(
        @NotEmpty @Size(max = 100)
        @Valid
        @Schema(description = "List of log events (max 100 per batch)")
        List<LogIngestionRequest> logs
) {
}
