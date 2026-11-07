package com.fincore.payments.exception;

public class LedgerStateException extends RuntimeException {
    public LedgerStateException() {
        super("The payment has no capture journal to reverse.");
    }
}
