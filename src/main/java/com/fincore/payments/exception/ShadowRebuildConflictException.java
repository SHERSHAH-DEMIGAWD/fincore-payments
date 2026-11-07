package com.fincore.payments.exception;

public class ShadowRebuildConflictException extends RuntimeException {
    public ShadowRebuildConflictException() { super("Shadow rebuild preconditions or command identity conflict."); }
}
