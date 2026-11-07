package com.fincore.payments.dto;

import com.fincore.payments.config.PaymentEventTransportProperties.Transport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectionReconciliationReport(Instant observedAt, String sourceName, String topic,
        String consumerName, Transport configuredTransport, String historicalCompleteness,
        String consumerStorageScope, UUID paymentFilter, long totalCandidates, int limit,
        UUID afterPaymentId, boolean hasMore, UUID nextAfterPaymentId, String pageStatus,
        ReconciliationGapContext recoveryGaps, List<PaymentReconciliation> payments) {
    public ProjectionReconciliationReport { payments = List.copyOf(payments); }
}
