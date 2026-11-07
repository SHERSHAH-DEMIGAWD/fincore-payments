package com.fincore.payments.service;

import com.fincore.payments.config.MerchantContext;
import com.fincore.payments.dto.*;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.LedgerRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** The HTTP payment boundary. Trusted headless operational services retain explicit global access. */
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class MerchantPaymentService {
    private final PaymentService payments;
    private final PaymentRepository ownership;
    private final LedgerService ledger;
    private final LedgerRepository journals;
    private final OutboxService outbox;
    private final PaymentEventConsumer consumer;
    private final PaymentRiskService risk;
    public MerchantPaymentService(PaymentService payments,PaymentRepository ownership,LedgerService ledger,
            LedgerRepository journals,OutboxService outbox,PaymentEventConsumer consumer,PaymentRiskService risk) {
        this.payments=payments; this.ownership=ownership; this.ledger=ledger;
        this.journals=journals; this.outbox=outbox; this.consumer=consumer;
        this.risk=risk;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public CreatePaymentResult createPayment(String key,CreatePaymentRequest request) {
        risk.requireAdmission(key,request);
        return payments.createPayment(MerchantContext.merchantId(),key,request);
    }
    @Transactional(readOnly=true)
    public PaymentResponse getPayment(UUID id) { requireOwned(id); return payments.getPayment(id); }
    public PaymentResponse authorizePayment(UUID id) { requireOwned(id); return payments.authorizePayment(id); }
    public PaymentResponse capturePayment(UUID id) { requireOwned(id); return payments.capturePayment(id); }
    public PaymentResponse failPayment(UUID id) { requireOwned(id); return payments.failPayment(id); }
    public PaymentResponse refundPayment(UUID id) { requireOwned(id); return payments.refundPayment(id); }
    @Transactional(readOnly=true)
    public List<LedgerTransactionResponse> getPaymentLedger(UUID id) { requireOwned(id); return ledger.getPaymentLedger(id); }
    @Transactional(readOnly=true)
    public List<OutboxEventResponse> getPaymentEvents(UUID id) { requireOwned(id); return outbox.getPaymentEvents(id); }
    @Transactional(readOnly=true)
    public PaymentProjectionResponse getProjection(UUID id) { requireOwned(id); return consumer.getProjection(id); }
    @Transactional(readOnly=true)
    public List<LedgerAccountBalanceResponse> getBalances(String currency) {
        if (currency==null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency.");
        return journals.merchantBalances(MerchantContext.merchantId(),currency);
    }
    private void requireOwned(UUID id) {
        if (!ownership.existsByIdAndMerchantId(id,MerchantContext.merchantId())) throw new PaymentNotFoundException(id);
    }
}
