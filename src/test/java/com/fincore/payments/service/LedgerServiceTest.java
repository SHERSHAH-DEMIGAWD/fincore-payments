package com.fincore.payments.service;

import com.fincore.payments.domain.LedgerOperation;
import com.fincore.payments.domain.LedgerTransaction;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.exception.LedgerStateException;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.LedgerRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {
    @Mock LedgerRepository ledger;
    @Mock PaymentRepository payments;
    LedgerService service;

    @BeforeEach
    void setUp() {
        service = new LedgerService(ledger, payments);
    }

    @Test
    void captureSavesBalancedJournalAndRefundReferencesItsCapture() {
        var payment = capturedPayment();
        service.postCapture(payment);
        var captureArgument = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(ledger).save(captureArgument.capture());
        var capture = captureArgument.getValue();
        assertThat(capture.operation()).isEqualTo(LedgerOperation.CAPTURE);
        assertThat(capture.entries()).hasSize(2);
        when(ledger.findCaptureId(payment.getId())).thenReturn(Optional.of(capture.id()));
        payment.refund();
        service.postRefund(payment);
        var journalArguments = ArgumentCaptor.forClass(LedgerTransaction.class);
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(2)).save(journalArguments.capture());
        assertThat(journalArguments.getAllValues().get(1).reversesTransactionId()).isEqualTo(capture.id());
    }

    @Test
    void missingCaptureIsBusinessConflict() {
        var payment = capturedPayment();
        payment.refund();
        assertThatThrownBy(() -> service.postRefund(payment)).isInstanceOf(LedgerStateException.class);
    }

    @Test
    void missingPaymentHasNoLedgerRead() {
        assertThatThrownBy(() -> service.getPaymentLedger(UUID.randomUUID()))
                .isInstanceOf(PaymentNotFoundException.class);
        verifyNoInteractions(ledger);
    }

    @Test
    void readsImmutableJournalDto() {
        var payment = capturedPayment();
        var capture = LedgerTransaction.capture(payment);
        when(payments.existsById(payment.getId())).thenReturn(true);
        when(ledger.findByPaymentId(payment.getId())).thenReturn(List.of(capture));
        assertThat(service.getPaymentLedger(payment.getId()).getFirst().id()).isEqualTo(capture.id());
    }

    @Test
    void invalidCurrencyCannotReachRepository() {
        assertThatThrownBy(() -> service.getBalances("inr")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ledger);
    }

    private Payment capturedPayment() {
        var payment = Payment.create("ORDER-1", BigDecimal.TEN, "INR");
        payment.authorize();
        payment.capture();
        return payment;
    }
}
