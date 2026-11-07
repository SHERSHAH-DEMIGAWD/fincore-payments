package com.fincore.payments.domain;

import com.fincore.payments.dto.PaymentResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Immutable v1 event envelope. The event ID is the deduplication identity for delivery retries. */
public record PaymentEvent(UUID eventId, PaymentEventType eventType, int schemaVersion, UUID paymentId,
        long paymentVersion, Instant occurredAt, PaymentResponse data) {
    public static PaymentEvent from(Payment payment) {
        long version = Objects.requireNonNull(payment.getVersion(), "Flush payment before recording its event.");
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.from(payment.getStatus()), 1,
                payment.getId(), version, payment.getUpdatedAt().truncatedTo(ChronoUnit.MICROS),
                PaymentResponse.from(payment));
    }
}
