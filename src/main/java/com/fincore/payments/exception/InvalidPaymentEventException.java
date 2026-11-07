package com.fincore.payments.exception;

public class InvalidPaymentEventException extends RuntimeException {
    public InvalidPaymentEventException(String message) {
        super(message);
    }
}
