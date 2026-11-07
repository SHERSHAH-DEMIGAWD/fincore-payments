package com.fincore.payments.exception;

public class EventOperationNotFoundException extends RuntimeException {
    public EventOperationNotFoundException() {
        super("Outbox event was not found.");
    }
}
