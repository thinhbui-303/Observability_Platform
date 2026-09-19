package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.AlertResponse;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.service.AlertLifecycleService;
import com.thinhbui303.observability.core.service.OperationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Endpoints for viewing and managing triggered alerts")
@SecurityRequirement(name = "bearerAuth")
public class AlertController {

    private final AlertLifecycleService service;
    private final OperationContext operationContext;

    public AlertController(AlertLifecycleService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @GetMapping
    @Operation(summary = "List alerts", description = "Returns a list of alerts filtered by service, environment, or status. Status is a single string, e.g. TRIGGERED, ACKNOWLEDGED, RESOLVED. Yêu cầu role: ADMIN, DEVOPS, DEVELOPER, VIEWER (authenticated).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved list of alerts"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<List<AlertResponse>>> list(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list(serviceId, environment, status)));
    }

    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS', 'ROLE_DEVELOPER')")
    @Operation(summary = "Acknowledge an alert", description = "Changes the status of a TRIGGERED alert to ACKNOWLEDGED. Yêu cầu role: ADMIN, DEVOPS, DEVELOPER.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Alert acknowledged successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Conflict (alert not in correct state for acknowledgment)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<AlertResponse>> acknowledge(
            @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.acknowledge(id, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "Resolve an alert", description = "Changes the status of a TRIGGERED or ACKNOWLEDGED alert to RESOLVED. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Alert resolved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Conflict (alert already resolved or cannot be resolved)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<AlertResponse>> resolve(
            @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.resolve(id, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }
}