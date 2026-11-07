package com.fincore.payments.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {
    @Test
    void captureAndRefundHaveExactlyReversedBalancedEntries() {
        Payment payment = capturedPayment();
        var capture = LedgerTransaction.capture(payment);
        payment.refund();
        var refund = LedgerTransaction.refund(payment, capture.id());
        assertThat(capture.operation()).isEqualTo(LedgerOperation.CAPTURE);
        assertThat(capture.reversesTransactionId()).isNull();
        assertThat(refund.reversesTransactionId()).isEqualTo(capture.id());
        assertThat(refund.source()).isEqualTo(LedgerSource.LIVE);
        assertThat(refund.paymentId()).isEqualTo(capture.paymentId());
        assertThat(capture.entries()).hasSize(2);
        for (var original : capture.entries()) {
            var reversed = refund.entries().stream().filter(entry -> entry.account() == original.account())
                    .findFirst().orElseThrow();
            assertThat(reversed.amount()).isEqualTo(original.amount());
            assertThat(reversed.currency()).isEqualTo(original.currency());
            assertThat(reversed.side()).isNotEqualTo(original.side());
        }
        assertThat(sum(capture, LedgerSide.DEBIT)).isEqualByComparingTo(sum(capture, LedgerSide.CREDIT));
        assertThat(sum(refund, LedgerSide.DEBIT)).isEqualByComparingTo(sum(refund, LedgerSide.CREDIT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "100000000000000000.00"})
    void rejectsInvalidEntryAmounts(String amount) {
        assertThatThrownBy(() -> entry(LedgerAccount.PROCESSOR_CLEARING, "INR", LedgerSide.DEBIT, amount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnbalancedMixedCurrencyAndWrongAccountDirections() {
        var capture = LedgerTransaction.capture(capturedPayment());
        var debit = entry(LedgerAccount.PROCESSOR_CLEARING, "INR", LedgerSide.DEBIT, "10");
        for (var wrongCredit : List.of(
                entry(LedgerAccount.MERCHANT_PAYABLE, "INR", LedgerSide.CREDIT, "9.99"),
                entry(LedgerAccount.MERCHANT_PAYABLE, "USD", LedgerSide.CREDIT, "10"),
                entry(LedgerAccount.PROCESSOR_CLEARING, "INR", LedgerSide.CREDIT, "10"),
                entry(LedgerAccount.MERCHANT_PAYABLE, "INR", LedgerSide.DEBIT, "10"))) {
            assertThatThrownBy(() -> withEntries(capture, List.of(debit, wrongCredit)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> withEntries(capture, List.of(debit))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withEntries(capture, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotPostBeforeFinancialStateTransition() {
        Payment payment = Payment.create("ORDER-1", BigDecimal.TEN, "INR");
        assertThatThrownBy(() -> LedgerTransaction.capture(payment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerTransaction.refund(payment, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        payment.authorize();
        assertThatThrownBy(() -> LedgerTransaction.capture(payment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundRequiresCaptureReferenceAndEntriesAreImmutable() {
        Payment payment = capturedPayment();
        var capture = LedgerTransaction.capture(payment);
        var mutable = new ArrayList<>(capture.entries());
        var copied = withEntries(capture, mutable);
        mutable.clear();
        assertThat(copied.entries()).hasSize(2);
        assertThatThrownBy(() -> copied.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        payment.refund();
        assertThatThrownBy(() -> LedgerTransaction.refund(payment, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private LedgerTransaction withEntries(LedgerTransaction original, List<LedgerEntry> entries) {
        return new LedgerTransaction(UUID.randomUUID(), original.paymentId(), original.operation(),
                original.currency(), original.amount(), null, LedgerSource.LIVE, Instant.now(), entries);
    }

    private LedgerEntry entry(LedgerAccount account, String currency, LedgerSide side, String amount) {
        return new LedgerEntry(UUID.randomUUID(), account, currency, side, new BigDecimal(amount));
    }

    private BigDecimal sum(LedgerTransaction journal, LedgerSide side) {
        return journal.entries().stream().filter(entry -> entry.side() == side)
                .map(LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Payment capturedPayment() {
        Payment payment = Payment.create("ORDER-1", BigDecimal.TEN, "INR");
        payment.authorize();
        payment.capture();
        return payment;
    }
}
