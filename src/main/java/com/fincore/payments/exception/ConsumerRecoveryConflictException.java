package com.fincore.payments.exception;
public class ConsumerRecoveryConflictException extends RuntimeException {
    public ConsumerRecoveryConflictException() {
        super("Consumer recovery preconditions or request identity conflict.");
    }
}
