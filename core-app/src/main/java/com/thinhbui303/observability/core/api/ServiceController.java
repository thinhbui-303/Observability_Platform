package com.thinhbui303.observability.core.api;

import com.thinhbui303.observability.core.api.dto.*;
import com.thinhbui303.observability.core.service.OperationContext;
import com.thinhbui303.observability.core.service.ServiceManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceManagementService service;
    private final OperationContext operationContext;

    public ServiceController(ServiceManagementService service, OperationContext operationContext) {
        this.service = service;
        this.operationContext = operationContext;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<UnifiedResponse<ServiceCreateResponse>> create(
            @Valid @RequestBody CreateServiceRequest req, HttpServletRequest request) {
        ServiceCreateResponse data = service.create(req, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", data));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DEVOPS')")
    public ResponseEntity<UnifiedResponse<List<ServiceResponse>>> list() {
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", service.list()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<UnifiedResponse<ServiceResponse>> updateStatus(
            @PathVariable String id, @Valid @RequestBody UpdateServiceStatusRequest req, HttpServletRequest request) {
        ServiceResponse data = service.updateStatus(id, req, operationContext.currentUsername(), operationContext.resolveIp(request));
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", data));
    }
}