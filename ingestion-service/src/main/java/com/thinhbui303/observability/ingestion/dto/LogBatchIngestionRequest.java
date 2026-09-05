package com.thinhbui303.observability.ingestion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record LogBatchIngestionRequest(
        @NotEmpty @Valid List<LogIngestionRequest> logs
) {
}
