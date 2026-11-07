package com.fincore.payments.exception;

import java.util.UUID;

public class PaymentProjectionNotFoundException extends RuntimeException {
    public PaymentProjectionNotFoundException(UUID paymentId) {
        super("Payment projection " + paymentId + " was not found.");
    }
}
