package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.AlertResponse;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.service.AlertLifecycleService;
import com.thinhbui303.observability.core.service.OperationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertLifecycleService service;
    private final OperationContext operationContext;

    public AlertController(AlertLifecycleService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @GetMapping
    public ResponseEntity<UnifiedResponse<List<AlertResponse>>> list(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list(serviceId, environment, status)));
    }

    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS', 'ROLE_DEVELOPER')")
    public ResponseEntity<UnifiedResponse<AlertResponse>> acknowledge(
            @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.acknowledge(id, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertResponse>> resolve(
            @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.resolve(id, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }
}