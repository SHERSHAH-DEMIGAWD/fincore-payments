package com.fincore.payments.exception;

public class ProjectionRoutingConflictException extends RuntimeException {
    public ProjectionRoutingConflictException() {
        this("Projection routing preconditions changed, a writer is busy, or the views are not safe to switch.");
    }

    protected ProjectionRoutingConflictException(String message) { super(message); }
}
