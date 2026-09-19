package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.AuditLogResponse;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.repository.UserRepository;
import com.thinhbui303.observability.core.service.AuditLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit Logs", description = "Endpoints for viewing system audit logs")
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {

    private final AuditLogQueryService queryService;
    private final UserRepository userRepository;

    public AuditLogController(AuditLogQueryService queryService, UserRepository userRepository) {
        this.queryService = queryService;
        this.userRepository = userRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    @Operation(summary = "List audit logs", description = "Returns a list of audit logs. DEVOPS role is restricted to viewing logs for services and alert-rules only. ADMIN can view all logs. Yêu cầu role: ADMIN, DEVOPS.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved list of audit logs"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = UnifiedResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden (missing ADMIN or DEVOPS role)", content = @Content(schema = @Schema(implementation = UnifiedResponse.class)))
    })
    public ResponseEntity<UnifiedResponse<List<AuditLogResponse>>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String resourceTarget,
            Authentication authentication) {
        boolean devopsScoped = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEVOPS"))
                && authentication.getAuthorities().stream()
                           .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                queryService.list(action, username, resourceTarget, devopsScoped)));
    }
}