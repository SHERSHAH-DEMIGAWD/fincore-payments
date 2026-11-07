package com.fincore.payments.domain;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    PARTIAL_CAPTURE,
    PARTIAL_REFUND,
    FAILED,
    REFUNDED
}
