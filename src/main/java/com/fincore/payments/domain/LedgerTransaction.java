package com.fincore.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An immutable two-leg journal; amount-operation principal and fee use distinct journals. */
public record LedgerTransaction(UUID id, UUID paymentId, LedgerOperation operation, String currency,
        BigDecimal amount, UUID reversesTransactionId, LedgerSource source, Instant createdAt,
        List<LedgerEntry> entries, UUID amountOperationId) {
    public LedgerTransaction(UUID id, UUID paymentId, LedgerOperation operation, String currency,
            BigDecimal amount, UUID reversesTransactionId, LedgerSource source, Instant createdAt, List<LedgerEntry> entries) {
        this(id,paymentId,operation,currency,amount,reversesTransactionId,source,createdAt,entries,null);
    }
    public LedgerTransaction {
        Objects.requireNonNull(id, "Transaction ID is required.");
        Objects.requireNonNull(paymentId, "Payment ID is required.");
        Objects.requireNonNull(operation, "Ledger operation is required.");
        Objects.requireNonNull(source, "Ledger source is required.");
        Objects.requireNonNull(createdAt, "Creation timestamp is required.");
        if ((operation != LedgerOperation.CAPTURE && operation != LedgerOperation.REFUND) != (amountOperationId != null))
            throw new IllegalArgumentException("Amount journals require their command identity; legacy journals cannot supply one.");
        entries = List.copyOf(entries);
        if ((!operation.isRefund() && reversesTransactionId != null)
                || (operation.isRefund() && reversesTransactionId == null)) {
            throw new IllegalArgumentException("A refund must reverse a capture; a capture has no reversal reference.");
        }
        if (entries.size() != 2 || entries.get(0).id().equals(entries.get(1).id())) {
            throw new IllegalArgumentException("A payment journal requires two distinct entries.");
        }
        long debits = entries.stream().filter(entry -> entry.side() == LedgerSide.DEBIT).count();
        long credits = entries.stream().filter(entry -> entry.side() == LedgerSide.CREDIT).count();
        if (debits != 1 || credits != 1) {
            throw new IllegalArgumentException("A payment journal requires one debit and one credit.");
        }
        for (var entry : entries) {
            if (!entry.currency().equals(currency) || amount == null || entry.amount().compareTo(amount) != 0
                    || entry.account() != (entry.side() == LedgerSide.DEBIT
                        ? operation.debitAccount() : operation.creditAccount())) {
                throw new IllegalArgumentException("Journal entries must match the amount, currency, and operation.");
            }
        }
        amount = entries.getFirst().amount();
    }

    public static LedgerTransaction amountPosting(UUID journalId, Payment payment, UUID operationId,
            LedgerOperation operation, BigDecimal amount, UUID reversalId) {
        if (operation != LedgerOperation.AMOUNT_CAPTURE && operation != LedgerOperation.AMOUNT_REFUND && operation != LedgerOperation.FEE)
            throw new IllegalArgumentException("An amount journal requires an amount operation.");
        return new LedgerTransaction(journalId,payment.getId(),operation,payment.getCurrency(),amount,reversalId,
                LedgerSource.LIVE,Instant.now(),List.of(
                new LedgerEntry(UUID.randomUUID(),operation.debitAccount(),payment.getCurrency(),LedgerSide.DEBIT,amount),
                new LedgerEntry(UUID.randomUUID(),operation.creditAccount(),payment.getCurrency(),LedgerSide.CREDIT,amount)),operationId);
    }

    public static LedgerTransaction capture(Payment payment) {
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new IllegalArgumentException("Capture journal requires a captured payment.");
        }
        return posting(payment, LedgerOperation.CAPTURE, null);
    }

    public static LedgerTransaction refund(Payment payment, UUID captureId) {
        if (payment.getStatus() != PaymentStatus.REFUNDED) {
            throw new IllegalArgumentException("Refund journal requires a refunded payment.");
        }
        return posting(payment, LedgerOperation.REFUND, captureId);
    }

    private static LedgerTransaction posting(Payment payment, LedgerOperation operation, UUID reversalId) {
        return new LedgerTransaction(UUID.randomUUID(), payment.getId(), operation, payment.getCurrency(),
                payment.getAmount(), reversalId, LedgerSource.LIVE, Instant.now(), List.of(
                    new LedgerEntry(UUID.randomUUID(), operation.debitAccount(), payment.getCurrency(),
                            LedgerSide.DEBIT, payment.getAmount()),
                    new LedgerEntry(UUID.randomUUID(), operation.creditAccount(), payment.getCurrency(),
                            LedgerSide.CREDIT, payment.getAmount())));
    }
}
