package com.fincore.payments.domain;

public enum PaymentEventType {
    PAYMENT_CREATED,
    PAYMENT_AUTHORIZED,
    PAYMENT_CAPTURED,
    PAYMENT_PARTIAL_CAPTURE,
    PAYMENT_PARTIAL_REFUND,
    PAYMENT_FAILED,
    PAYMENT_REFUNDED;

    public static PaymentEventType from(PaymentStatus status) {
        return valueOf("PAYMENT_" + status.name());
    }
}
