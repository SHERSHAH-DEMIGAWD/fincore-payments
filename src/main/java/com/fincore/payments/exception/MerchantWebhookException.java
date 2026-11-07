package com.fincore.payments.exception;

public final class MerchantWebhookException extends RuntimeException {
    private final int status;
    private final String code;
    public MerchantWebhookException(int status,String code) { super("Merchant webhook operation was refused."); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
}
