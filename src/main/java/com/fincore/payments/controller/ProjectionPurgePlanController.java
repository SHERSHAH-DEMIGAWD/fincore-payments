package com.fincore.payments.controller;

import com.fincore.payments.dto.ProjectionPurgePlan;
import com.fincore.payments.dto.ProjectionPurgePlanCommand;
import com.fincore.payments.service.ProjectionPurgePlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/projection-routing/purge-plans")
public class ProjectionPurgePlanController {
    private final ProjectionPurgePlanService plans;
    public ProjectionPurgePlanController(ProjectionPurgePlanService plans) { this.plans=plans; }

    @PostMapping
    public ProjectionPurgePlan.Result create(@Valid @RequestBody ProjectionPurgePlanCommand command,Principal principal) {
        return plans.create(command,principal.getName());
    }
    @GetMapping("/{requestId}")
    public ProjectionPurgePlan get(@PathVariable UUID requestId) { return plans.get(requestId); }
    @GetMapping("/{requestId}/review")
    public ProjectionPurgePlan.Review review(@PathVariable UUID requestId) { return plans.review(requestId); }
}
