package com.fincore.payments.exception;

public final class PaymentAccountingException extends RuntimeException {
    private final int status;
    public PaymentAccountingException(int status,String message) { super(message); this.status=status; }
    public int status() { return status; }
}
