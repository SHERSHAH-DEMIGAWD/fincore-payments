package com.fincore.payments.dto;

import com.fincore.payments.service.ProjectionRoutingMetrics;

public record ProjectionOperatingReport(int maxComparisonRowsPerDataset, int reviewAfterDays,
        int maxEvidencePageSize, int transactionTimeoutSeconds, String comparisonBudgetScope,
        ProjectionRoutingMetrics.Snapshot httpCommands) { }
