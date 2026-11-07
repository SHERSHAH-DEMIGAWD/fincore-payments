package com.fincore.payments.exception;

public class PaymentEventConflictException extends RuntimeException {
    public PaymentEventConflictException() {
        super("Payment event identity conflicts with an existing consumer receipt.");
    }
}
