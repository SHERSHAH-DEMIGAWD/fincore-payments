package com.fincore.payments.service;

import com.fincore.payments.domain.LedgerTransaction;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.dto.LedgerAccountBalanceResponse;
import com.fincore.payments.dto.LedgerTransactionResponse;
import com.fincore.payments.exception.LedgerStateException;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.LedgerRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {
    private final LedgerRepository ledgerRepository;
    private final PaymentRepository paymentRepository;

    public LedgerService(LedgerRepository ledgerRepository, PaymentRepository paymentRepository) {
        this.ledgerRepository = ledgerRepository;
        this.paymentRepository = paymentRepository;
    }

    // Posting must join the payment transition's transaction; never commit financial records separately.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postCapture(Payment payment) {
        ledgerRepository.save(LedgerTransaction.capture(payment));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postRefund(Payment payment) {
        UUID captureId = ledgerRepository.findCaptureId(payment.getId()).orElseThrow(LedgerStateException::new);
        ledgerRepository.save(LedgerTransaction.refund(payment, captureId));
    }

    @Transactional(readOnly = true)
    public List<LedgerTransactionResponse> getPaymentLedger(UUID paymentId) {
        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return ledgerRepository.findByPaymentId(paymentId).stream().map(LedgerTransactionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<LedgerAccountBalanceResponse> getBalances(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must contain exactly three uppercase letters.");
        }
        return ledgerRepository.balances(currency);
    }
}
