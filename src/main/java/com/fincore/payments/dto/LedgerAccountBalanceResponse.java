package com.fincore.payments.dto;

import com.fincore.payments.domain.LedgerAccount;
import com.fincore.payments.domain.LedgerSide;

import java.math.BigDecimal;

public record LedgerAccountBalanceResponse(LedgerAccount account, String currency, LedgerSide normalSide,
        BigDecimal totalDebits, BigDecimal totalCredits, BigDecimal balance) {
}
