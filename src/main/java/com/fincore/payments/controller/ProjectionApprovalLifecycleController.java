package com.fincore.payments.controller;

import com.fincore.payments.dto.ProjectionApprovalLifecycle;
import com.fincore.payments.service.ProjectionApprovalLifecycleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/projection-routing/approvals")
public class ProjectionApprovalLifecycleController {
    private final ProjectionApprovalLifecycleService lifecycle;
    public ProjectionApprovalLifecycleController(ProjectionApprovalLifecycleService lifecycle) { this.lifecycle=lifecycle; }

    @PostMapping("/{approvalId}/revoke")
    public ProjectionApprovalLifecycle.Result revoke(@PathVariable UUID approvalId,
            @Valid @RequestBody ProjectionApprovalLifecycle.RevocationCommand command,Principal principal) {
        return lifecycle.revoke(approvalId,command,principal.getName());
    }

    @GetMapping("/{approvalId}/revocation")
    public ProjectionApprovalLifecycle.Revocation revocation(@PathVariable UUID approvalId) {
        return lifecycle.revocation(approvalId);
    }
}
