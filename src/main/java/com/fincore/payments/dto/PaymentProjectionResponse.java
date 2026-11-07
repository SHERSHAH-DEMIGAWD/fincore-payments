package com.fincore.payments.dto;

import com.fincore.payments.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentProjectionResponse(
        UUID paymentId,
        UUID lastEventId,
        long paymentVersion,
        PaymentStatus status,
        String merchantReference,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        Instant projectedAt
) { }
