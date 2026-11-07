package com.fincore.payments.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID paymentId) {
        super("Payment " + paymentId + " was not found.");
    }
}
