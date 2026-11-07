package com.fincore.payments.controller;

import com.fincore.payments.dto.ProjectionPromotionCommand;
import com.fincore.payments.dto.ProjectionRollbackCommand;
import com.fincore.payments.dto.ProjectionRoutingResult;
import com.fincore.payments.dto.ProjectionRoutingView;
import com.fincore.payments.dto.ProjectionReplacementCommand;
import com.fincore.payments.dto.ProjectionRetirementCommand;
import com.fincore.payments.dto.ProjectionTargetView;
import com.fincore.payments.service.ProjectionRoutingService;
import com.fincore.payments.service.ProjectionRoutingMetrics;
import com.fincore.payments.service.ProjectionRoutingMetrics.Action;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/projection-routing")
public class ProjectionRoutingController {
    private final ProjectionRoutingService routing;
    private final ProjectionRoutingMetrics metrics;

    public ProjectionRoutingController(ProjectionRoutingService routing,ProjectionRoutingMetrics metrics) {
        this.routing = routing;
        this.metrics = metrics;
    }

    @GetMapping
    public ProjectionRoutingView status() {
        return routing.status();
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping("/promote")
    public ProjectionRoutingResult promote(@Valid @RequestBody ProjectionPromotionCommand command, Principal principal) {
        return metrics.measure(Action.PROMOTE,() -> routing.promote(command, principal.getName()));
    }

    @PostMapping("/rollback")
    public ProjectionRoutingResult rollback(@Valid @RequestBody ProjectionRollbackCommand command, Principal principal) {
        return metrics.measure(Action.ROLLBACK,() -> routing.rollback(command, principal.getName()));
    }

    @PostMapping("/replace")
    public ProjectionRoutingResult replace(@Valid @RequestBody ProjectionReplacementCommand command, Principal principal) {
        return metrics.measure(Action.REPLACE,() -> routing.replace(command, principal.getName()));
    }

    @PostMapping("/retire")
    public ProjectionRoutingResult retire(@Valid @RequestBody ProjectionRetirementCommand command, Principal principal) {
        return metrics.measure(Action.RETIRE,() -> routing.retire(command, principal.getName()));
    }

    @GetMapping("/targets/{runId}")
    public ProjectionTargetView target(@PathVariable UUID runId) {
        return routing.target(runId);
    }
}
