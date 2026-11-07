package com.fincore.payments.controller;

import com.fincore.payments.config.ProjectionOperationsProperties;
import com.fincore.payments.dto.ProjectionEvidenceReport;
import com.fincore.payments.dto.ProjectionOperatingReport;
import com.fincore.payments.service.ProjectionEvidenceService;
import com.fincore.payments.service.ProjectionRoutingMetrics;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/projection-routing")
public class ProjectionEvidenceController {
    private final ProjectionEvidenceService evidence;
    private final ProjectionOperationsProperties properties;
    private final ProjectionRoutingMetrics metrics;

    public ProjectionEvidenceController(ProjectionEvidenceService evidence,ProjectionOperationsProperties properties,
            ProjectionRoutingMetrics metrics) {
        this.evidence=evidence; this.properties=properties; this.metrics=metrics;
    }

    @GetMapping("/evidence")
    public ProjectionEvidenceReport review(@RequestParam(required=false) UUID afterRunId,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return evidence.review(afterRunId,limit);
    }

    @GetMapping("/operating-limits")
    public ProjectionOperatingReport limits() {
        return new ProjectionOperatingReport(properties.maxComparisonRows(),properties.reviewAfterDays(),
                ProjectionEvidenceService.MAX_PAGE_SIZE,30,
                "SOURCE_PAYMENTS_BASELINE_AND_SELECTED_TARGET_PROJECTIONS_AND_CURRENT_MANIFESTS",
                metrics.snapshot());
    }
}
