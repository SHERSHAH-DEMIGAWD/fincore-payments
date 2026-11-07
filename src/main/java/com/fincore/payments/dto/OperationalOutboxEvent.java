package com.fincore.payments.dto;

import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.domain.PaymentEventType;

import java.time.Instant;
import java.util.UUID;

public record OperationalOutboxEvent(UUID eventId, UUID paymentId, long paymentVersion,
        PaymentEventType eventType, OutboxStatus status, int attempts, int cycleAttempts,
        long retryGeneration, Instant occurredAt, Instant nextAttemptAt, Instant deliveredAt,
        String lastErrorCode, UUID oldestBlockingEventId, Long latestProjectionVersion) { }
