package com.fincore.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record ShadowRebuildView(UUID runId, String consumerName, String sourceName, String topic,
        long generation, String state, Instant createdAt, Instant plannedAt,
        long totalItems, long pendingItems, long appliedItems, long blockedItems,
        long historyIncompleteItems, UUID nextPaymentId, ReconciliationGapContext plannedRecoveryGaps) { }
