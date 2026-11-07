package com.fincore.payments.dto;

import java.util.List;
import java.util.UUID;

public record PaymentReconciliation(UUID paymentId, Long sourceVersion, Long projectionVersion,
        long sourceEventCount, Long firstSourceVersion, Long lastSourceVersion,
        long matchingReceipts, long missingReceipts, long conflictingReceipts, long unmatchedReceipts,
        Long latestReceiptVersion, long pendingSourceEvents, long deadSourceEvents,
        boolean retainedHistoryContiguous, UUID latestSourceEventId, boolean latestSourceReplayCandidate,
        List<ReconciliationFinding> findings) {
    public PaymentReconciliation { findings = List.copyOf(findings); }
}
