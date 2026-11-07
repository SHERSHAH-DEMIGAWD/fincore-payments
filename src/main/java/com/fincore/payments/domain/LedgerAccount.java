package com.fincore.payments.domain;

/** Pooled platform accounts, not merchant-specific wallets or actual bank balances. */
public enum LedgerAccount {
    PROCESSOR_CLEARING(LedgerSide.DEBIT),
    MERCHANT_PAYABLE(LedgerSide.CREDIT),
    PLATFORM_FEE_REVENUE(LedgerSide.CREDIT);

    private final LedgerSide normalSide;

    LedgerAccount(LedgerSide normalSide) {
        this.normalSide = normalSide;
    }

    public LedgerSide normalSide() {
        return normalSide;
    }
}
