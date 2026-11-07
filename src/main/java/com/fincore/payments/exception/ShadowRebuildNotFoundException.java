package com.fincore.payments.exception;

public class ShadowRebuildNotFoundException extends RuntimeException {
    public ShadowRebuildNotFoundException() { super("Shadow rebuild does not exist in the configured scope."); }
}
