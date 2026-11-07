package com.fincore.payments.exception;
public class ConsumerRecoveryNotFoundException extends RuntimeException {
    public ConsumerRecoveryNotFoundException() {
        super("Consumer failure not found in the configured scope.");
    }
}
