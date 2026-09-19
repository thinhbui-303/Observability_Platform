package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.service.OperationContext;
import com.thinhbui303.observability.core.service.ServiceManagementService;
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
@RequestMapping("/api/v1/services")
@Tag(name = "Service Management", description = "Endpoints for managing registered services")
@SecurityRequirement(name = "bearerAuth")
public class ServiceController {

    private final ServiceManagementService service;
    private final OperationContext operationContext;

    public ServiceController(ServiceManagementService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Register a new service", description = "Registers a new service and returns a plaintext API Key. WARNING: The plaintext API Key is returned only once in this response. Yêu cầu role: ADMIN.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Service registered successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden (missing ADMIN role)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Service already exists", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<ServiceCreateResponse>> create(
            @Valid @RequestBody CreateServiceRequest req, HttpServletRequest request) {
        ServiceCreateResponse data = service.create(req, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", data));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "List all services", description = "Returns a list of all registered services. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved list of services"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden (missing ADMIN or DEVOPS role)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<List<ServiceResponse>>> list() {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Update service status", description = "Updates the status of a specific service. Yêu cầu role: ADMIN.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden (missing ADMIN role)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Service not found", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<ServiceResponse>> updateStatus(
            @PathVariable String id, @Valid @RequestBody UpdateServiceStatusRequest req, HttpServletRequest request) {
        ServiceResponse data = service.updateStatus(id, req, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", data));
    }
}