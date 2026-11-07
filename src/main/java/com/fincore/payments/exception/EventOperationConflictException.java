package com.fincore.payments.exception;

public class EventOperationConflictException extends RuntimeException {
    public EventOperationConflictException() {
        super("Requeue conflicts with the event state, retry generation, or an earlier request.");
    }
}
