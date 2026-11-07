package com.fincore.payments.exception;

import java.util.UUID;

/** Carries retry identity outside a rolled-back transaction without exposing transport details. */
public class OutboxPublishException extends RuntimeException {
    private final UUID eventId;
    private final int expectedAttempts;

    public OutboxPublishException(UUID eventId, int expectedAttempts, RuntimeException cause) {
        super("Payment event delivery failed.", cause);
        this.eventId = eventId;
        this.expectedAttempts = expectedAttempts;
    }

    public UUID eventId() { return eventId; }
    public int expectedAttempts() { return expectedAttempts; }
}
