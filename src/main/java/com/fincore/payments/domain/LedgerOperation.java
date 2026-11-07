package com.fincore.payments.domain;

public enum LedgerOperation {
    CAPTURE, REFUND, AMOUNT_CAPTURE, AMOUNT_REFUND, FEE;

    public boolean isRefund() { return this == REFUND || this == AMOUNT_REFUND; }
    public boolean isCapture() { return this == CAPTURE || this == AMOUNT_CAPTURE; }

    public LedgerAccount debitAccount() {
        return isCapture() ? LedgerAccount.PROCESSOR_CLEARING : LedgerAccount.MERCHANT_PAYABLE;
    }

    public LedgerAccount creditAccount() {
        return isCapture() ? LedgerAccount.MERCHANT_PAYABLE
                : this == FEE ? LedgerAccount.PLATFORM_FEE_REVENUE : LedgerAccount.PROCESSOR_CLEARING;
    }
}
