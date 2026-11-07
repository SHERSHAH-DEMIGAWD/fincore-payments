package com.fincore.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record ShadowRebuildValidation(UUID runId, long generation, String consumerName, Instant observedAt,
        String status, String historicalCompleteness, String promotion, long manifestItems,
        long pendingItems, long blockedItems, long shadowMismatches, long sourceChanged, long sourceAdded,
        long sourceRemoved, long unplannedShadowRows, long historyIncompleteItems, ReconciliationGapContext recoveryGaps) { }
