package com.fincore.payments.exception;

public class ProjectionRoutingNotFoundException extends RuntimeException {
    public ProjectionRoutingNotFoundException() { super("Projection rebuild was not found in this source scope."); }
}
