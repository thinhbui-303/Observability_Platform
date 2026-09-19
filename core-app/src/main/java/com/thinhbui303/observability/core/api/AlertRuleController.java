package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.service.AlertRuleManagementService;
import com.thinhbui303.observability.core.service.OperationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alert-rules")
@Tag(name = "Alert Rules", description = "Endpoints for managing alert rules")
@SecurityRequirement(name = "bearerAuth")
public class AlertRuleController {

    private final AlertRuleManagementService service;
    private final OperationContext operationContext;

    public AlertRuleController(AlertRuleManagementService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "Create an alert rule", description = "Creates a new alert rule. Note: conditionValue is required if conditionType is PATTERN_MATCH. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Alert rule created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden (missing ADMIN or DEVOPS role)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> create(
            @Valid @RequestBody CreateAlertRuleRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.create(req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "Update an alert rule", description = "Updates an existing alert rule. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Alert rule updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Alert rule not found", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateAlertRuleRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.update(id, req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "Enable or disable an alert rule", description = "Toggles the enabled state of an alert rule. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Alert rule state updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Alert rule not found", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> setEnabled(
            @PathVariable Long id, @Valid @RequestBody SetAlertRuleEnabledRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.setEnabled(id, req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "Delete an alert rule", description = "Deletes an alert rule. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Alert rule deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Alert rule not found", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<Void>> delete(@PathVariable Long id, HttpServletRequest request) {
        service.delete(id, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", null));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "List alert rules", description = "Returns a list of alert rules, optionally filtered by service and environment. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved list of alert rules"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<List<AlertRuleResponse>>> list(
            @RequestParam(required = false) String service, @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", this.service.list(service, environment)));
    }
}
