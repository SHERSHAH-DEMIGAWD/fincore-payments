package com.fincore.payments.dto;

import com.fincore.payments.domain.LedgerEntry;
import com.fincore.payments.domain.LedgerOperation;
import com.fincore.payments.domain.LedgerSource;
import com.fincore.payments.domain.LedgerTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionResponse(UUID id, UUID paymentId, LedgerOperation operation, String currency,
        BigDecimal amount, UUID reversesTransactionId, LedgerSource source, Instant createdAt,
        List<LedgerEntry> entries, UUID amountOperationId) {
    public static LedgerTransactionResponse from(LedgerTransaction transaction) {
        return new LedgerTransactionResponse(transaction.id(), transaction.paymentId(), transaction.operation(),
                transaction.currency(), transaction.amount(), transaction.reversesTransactionId(),
                transaction.source(), transaction.createdAt(), transaction.entries(), transaction.amountOperationId());
    }
}
