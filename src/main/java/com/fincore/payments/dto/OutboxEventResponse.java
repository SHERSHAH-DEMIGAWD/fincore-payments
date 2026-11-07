package com.fincore.payments.dto;

import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.domain.PaymentEvent;

import java.time.Instant;

public record OutboxEventResponse(PaymentEvent event, OutboxStatus status, int attempts,
        Instant nextAttemptAt, Instant deliveredAt, String lastErrorCode) {
}
