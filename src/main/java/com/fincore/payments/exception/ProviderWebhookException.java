package com.fincore.payments.exception;

public final class ProviderWebhookException extends RuntimeException {
    private final int status;
    private final String code;
    public ProviderWebhookException(int status,String code) {
        super("Provider webhook was refused."); this.status=status; this.code=code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
