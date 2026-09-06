package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.AuditLogResponse;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.repository.UserRepository;
import com.thinhbui303.observability.core.service.AuditLogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService queryService;
    private final UserRepository userRepository;

    public AuditLogController(AuditLogQueryService queryService, UserRepository userRepository) {
        this.queryService = queryService;
        this.userRepository = userRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
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