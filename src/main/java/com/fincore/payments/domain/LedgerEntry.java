package com.fincore.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record LedgerEntry(UUID id, LedgerAccount account, String currency, LedgerSide side, BigDecimal amount) {
    public LedgerEntry {
        Objects.requireNonNull(id, "Entry ID is required.");
        Objects.requireNonNull(account, "Ledger account is required.");
        Objects.requireNonNull(side, "Ledger side is required.");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Ledger currency must be three uppercase letters.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger amounts must be positive.");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Ledger amounts must not be rounded.");
        }
        if (amount.precision() > 19) {
            throw new IllegalArgumentException("Ledger entry amount exceeds payment precision.");
        }
    }
}
