package com.thinhbui303.observability.core.api.controller;

import com.thinhbui303.observability.core.api.dto.DlqMessageDto;
import com.thinhbui303.observability.core.api.dto.DlqProcessRequest;
import com.thinhbui303.observability.core.api.dto.UnifiedResponse;
import com.thinhbui303.observability.core.service.DlqService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dlq")
@PreAuthorize("hasAnyRole('ADMIN', 'DEVOPS')")
public class DlqController {

    private final DlqService dlqService;

    public DlqController(DlqService dlqService) {
        this.dlqService = dlqService;
    }

    @GetMapping
    public ResponseEntity<UnifiedResponse<List<DlqMessageDto>>> getDlqMessages(
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        
        List<DlqMessageDto> messages = dlqService.getDlqMessages(limit);
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", messages));
    }

    @PostMapping("/process")
    public ResponseEntity<UnifiedResponse<String>> processDlqMessages(
            @Valid @RequestBody DlqProcessRequest processReq,
            Authentication authentication,
            HttpServletRequest request) {
        
        String username = authentication.getName();
        String ip = request.getRemoteAddr();
        
        int processedCount = dlqService.processDlqMessages(processReq.action(), processReq.limit(), username, ip);
        
        String message = String.format("Successfully processed %d records with action %s", processedCount, processReq.action());
        return ResponseEntity.ok(new UnifiedResponse<>("SUCCESS", message));
    }
}
