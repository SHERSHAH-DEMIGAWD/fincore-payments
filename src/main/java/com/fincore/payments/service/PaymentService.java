package com.fincore.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.CreatePaymentResult;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.exception.IdempotencyConflictException;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.IdempotencyRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;

    public PaymentService(PaymentRepository paymentRepository, IdempotencyRepository idempotencyRepository,
            ObjectMapper objectMapper, LedgerService ledgerService, OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatePaymentResult createPayment(String idempotencyKey, CreatePaymentRequest request) {
        return createInScope(null,idempotencyKey,request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatePaymentResult createPayment(UUID merchantId,String idempotencyKey,CreatePaymentRequest request) {
        java.util.Objects.requireNonNull(merchantId,"Merchant identity is required.");
        return createInScope(merchantId.equals(com.fincore.payments.domain.MerchantIdentity.LEGACY_ID) ? null : merchantId,
                idempotencyKey,request);
    }

    private CreatePaymentResult createInScope(UUID merchantId,String idempotencyKey,CreatePaymentRequest request) {
        IdempotencyKey.validate(idempotencyKey);
        // Validate money/domain input before claiming. Nothing has been persisted yet.
        Payment payment = merchantId==null ? Payment.create(request.merchantReference(), request.amount(), request.currency())
                : Payment.create(merchantId,request.merchantReference(),request.amount(),request.currency());
        String fingerprint = PaymentRequestFingerprint.of(request);
        PaymentResponse response = PaymentResponse.from(payment);
        try {
            String snapshot = objectMapper.writeValueAsString(response);
            boolean claimed=merchantId==null
                    ? idempotencyRepository.claim(idempotencyKey,fingerprint,payment.getId(),snapshot,payment.getCreatedAt())
                    : idempotencyRepository.claim(merchantId,idempotencyKey,fingerprint,payment.getId(),snapshot,payment.getCreatedAt());
            if (claimed) {
                paymentRepository.saveAndFlush(payment);
                outboxService.record(payment);
                log.debug("New payment prepared for commit: {}", payment.getId());
                return new CreatePaymentResult(response, true);
            }
            // ON CONFLICT waits for an uncommitted rival. At READ COMMITTED this separate SELECT
            // then sees its committed snapshot. If the rival rolls back, our INSERT claims the key.
            // Records are never expired/deleted here; JPA and JDBC share this transaction.
            var original = merchantId==null ? idempotencyRepository.find(idempotencyKey)
                    : idempotencyRepository.find(merchantId,idempotencyKey);
            if (!original.fingerprint().equals(fingerprint)) {
                log.debug("Conflicting reuse of a payment idempotency key");
                throw new IdempotencyConflictException();
            }
            PaymentResponse replay = objectMapper.readValue(original.responseBody(), PaymentResponse.class);
            log.debug("Idempotent payment replay: {}", replay.id());
            return new CreatePaymentResult(replay, false);
        } catch (JsonProcessingException exception) {
            // Unchecked exception rolls back the claim and payment together.
            throw new IllegalStateException("Could not process the saved payment response.", exception);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId) {
        return PaymentResponse.from(findPayment(paymentId));
    }

    @Transactional
    public PaymentResponse authorizePayment(UUID paymentId) {
        requireManualPayment(paymentId);
        return transition(paymentId, Payment::authorize);
    }

    @Transactional
    public PaymentResponse capturePayment(UUID paymentId) {
        requireManualPayment(paymentId);
        return captureInTransaction(paymentId);
    }

    private PaymentResponse captureInTransaction(UUID paymentId) {
        Payment payment = findPayment(paymentId);
        payment.capture();
        // Win the version-checked update before writing ledger records. A posting failure still
        // rolls this update back because both operations share the same transaction.
        paymentRepository.flush();
        ledgerService.postCapture(payment);
        outboxService.record(payment);
        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse failPayment(UUID paymentId) {
        requireManualPayment(paymentId);
        return transition(paymentId, Payment::fail);
    }

    @Transactional
    public PaymentResponse refundPayment(UUID paymentId) {
        requireManualPayment(paymentId);
        return refundInTransaction(paymentId);
    }

    private PaymentResponse refundInTransaction(UUID paymentId) {
        Payment payment = findPayment(paymentId);
        payment.refund();
        paymentRepository.flush();
        ledgerService.postRefund(payment);
        outboxService.record(payment);
        return PaymentResponse.from(payment);
    }

    private PaymentResponse transition(UUID paymentId, Consumer<Payment> operation) {
        Payment payment = findPayment(paymentId);
        operation.accept(payment);
        // The managed entity is updated by dirty checking. Flush detects version conflicts here.
        paymentRepository.flush();
        outboxService.record(payment);
        return PaymentResponse.from(payment);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public PaymentResponse applyProviderResult(UUID paymentId,UUID operationId,
            com.fincore.payments.dto.ProviderOperation.Action action,com.fincore.payments.dto.ProviderOperation.Outcome outcome) {
        if (!paymentRepository.providerApplicationAllowed(paymentId,operationId))
            throw new com.fincore.payments.exception.ProviderOperationConflictException("A confirmed provider result is required in this transaction.");
        if (outcome==com.fincore.payments.dto.ProviderOperation.Outcome.DECLINED) {
            if (action!=com.fincore.payments.dto.ProviderOperation.Action.AUTHORIZE) throw new IllegalArgumentException("Declined operation has no local effect.");
            return transition(paymentId,Payment::fail);
        }
        return switch (action) {
            case AUTHORIZE -> transition(paymentId,Payment::authorize);
            case CAPTURE -> captureInTransaction(paymentId);
            case REFUND -> refundInTransaction(paymentId);
        };
    }

    private void requireManualPayment(UUID paymentId) {
        if (paymentRepository.isAmountManaged(paymentId))
            throw new com.fincore.payments.exception.PaymentAccountingException(409,"Use amount operations for this payment.");
        if (paymentRepository.isProviderManaged(paymentId))
            throw new com.fincore.payments.exception.ProviderOperationConflictException("Use provider operations for this provider-managed payment.");
    }

    private Payment findPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
