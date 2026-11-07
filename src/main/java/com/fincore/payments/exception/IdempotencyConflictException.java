package com.fincore.payments.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("The supplied idempotency key was already used for a different payment request.");
    }
}
