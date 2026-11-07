package com.fincore.payments.exception;

public class ProjectionComparisonBudgetException extends ProjectionRoutingConflictException {
    public ProjectionComparisonBudgetException() {
        super("Projection comparison row budget exceeded. Review operating limits before a new decision.");
    }
}
