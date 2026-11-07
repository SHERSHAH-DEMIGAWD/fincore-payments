package com.fincore.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.PaymentProjectionResponse;
import com.fincore.payments.exception.PaymentEventConflictException;
import com.fincore.payments.exception.PaymentProjectionNotFoundException;
import com.fincore.payments.repository.PaymentEventConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentEventConsumer {
    // Durable logical processing identity; Kafka consumer-group changes do not reset deduplication.
    public static final String CONSUMER_NAME = "payment-projection-v1";

    private final PaymentEventConsumerRepository receipts;
    private final PaymentEventValidator validator;
    private final ObjectMapper mapper;

    public PaymentEventConsumer(PaymentEventConsumerRepository receipts, PaymentEventValidator validator,
            ObjectMapper mapper) {
        this.receipts = receipts;
        this.validator = validator;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean consume(PaymentEvent event) {
        // REQUIRED preserves position/receipt/success atomicity. Reject an inherited old snapshot
        // instead of silently missing a route enrollment committed while this caller was active.
        receipts.requireReadCommitted();
        receipts.lockRouting();
        validator.validate(event);
        String payload = serialize(event);
        boolean inserted = apply(CONSUMER_NAME,event,payload);
        // Enrollment survives rollback. Duplicate live receipts must also visit the warm target.
        receipts.maintainedShadow().ifPresent(shadow -> {
            apply(shadow,event,payload);
            if (!receipts.maintainedPaymentMatches(shadow,event.paymentId())) throw new PaymentEventConflictException();
        });
        return inserted;
    }

    private boolean apply(String consumer,PaymentEvent event,String payload) {
        if (!receipts.insertReceipt(consumer,event,payload)) {
            if (!receipts.matchesExistingReceipt(consumer,event,payload)) throw new PaymentEventConflictException();
            return false;
        }
        // The receipt and snapshot commit together. An older event is still recorded, while the
        // conditional upsert preserves the newest full snapshot without replaying ledger writes.
        receipts.applyProjection(consumer, event);
        return true;
    }

    @Transactional(readOnly = true)
    public PaymentProjectionResponse getProjection(UUID paymentId) {
        return receipts.findRoutedProjection(paymentId)
                .orElseThrow(() -> new PaymentProjectionNotFoundException(paymentId));
    }

    private String serialize(PaymentEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize a consumed payment event.", exception);
        }
    }
}
