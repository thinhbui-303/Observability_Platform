package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.service.AlertRuleManagementService;
import com.thinhbui303.observability.core.service.OperationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alert-rules")
public class AlertRuleController {

    private final AlertRuleManagementService service;
    private final OperationContext operationContext;

    public AlertRuleController(AlertRuleManagementService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> create(
            @Valid @RequestBody CreateAlertRuleRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.create(req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateAlertRuleRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.update(id, req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<AlertRuleResponse>> setEnabled(
            @PathVariable Long id, @Valid @RequestBody SetAlertRuleEnabledRequest req, HttpServletRequest request) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS",
                service.setEnabled(id, req, operationContext.currentUsername(), operationContext.resolveIp(request))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<Void>> delete(@PathVariable Long id, HttpServletRequest request) {
        service.delete(id, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", null));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<List<AlertRuleResponse>>> list(
            @RequestParam(required = false) String service, @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", this.service.list(service, environment)));
    }
}
