package com.fincore.payments.controller;

import com.fincore.payments.dto.ProjectionPreservation;
import com.fincore.payments.service.ProjectionPreservationService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/projection-routing")
public class ProjectionPreservationController {
    private final ProjectionPreservationService preservation;
    public ProjectionPreservationController(ProjectionPreservationService preservation) { this.preservation=preservation; }
    @PostMapping("/purge-plans/{planId}/preservations")
    public ProjectionPreservation.Result preserve(@PathVariable UUID planId,
            @Valid @RequestBody ProjectionPreservation.Command command,Principal principal) {
        return preservation.preserve(planId,command,principal.getName());
    }
    @PostMapping("/purge-plans/{planId}/approvals")
    public ProjectionPreservation.ApprovalResult approve(@PathVariable UUID planId,
            @Valid @RequestBody ProjectionPreservation.ApprovalCommand command,Principal principal) {
        return preservation.approve(planId,command,principal.getName());
    }
    @GetMapping("/preservations/{requestId}")
    public ProjectionPreservation get(@PathVariable UUID requestId) { return preservation.get(requestId); }
    @GetMapping("/preservations/{requestId}/export")
    public ResponseEntity<byte[]> download(@PathVariable UUID requestId) {
        var file=preservation.download(requestId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(file.bytes().length)
                .cacheControl(CacheControl.noStore()).header("X-Content-SHA256",file.sha256())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"projection-preservation-"+file.preservationId()+".json\"")
                .body(file.bytes());
    }
    @GetMapping("/approvals/{requestId}")
    public ProjectionPreservation.Approval approval(@PathVariable UUID requestId) { return preservation.approval(requestId); }
    @GetMapping("/approvals/{requestId}/review")
    public ProjectionPreservation.ApprovalReview review(@PathVariable UUID requestId) { return preservation.reviewApproval(requestId); }
}
