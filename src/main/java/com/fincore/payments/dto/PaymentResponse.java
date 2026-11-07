package com.fincore.payments.dto;

import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String merchantReference,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getMerchantReference(),
                payment.getAmount(), payment.getCurrency(), payment.getStatus(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
