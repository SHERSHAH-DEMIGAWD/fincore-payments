package com.fincore.payments.service;

import com.fincore.payments.exception.OutboxPublishException;
import com.fincore.payments.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDeliveryWorker {
    private final OutboxRepository outbox;
    private final PaymentEventPublisher publisher;

    public OutboxDeliveryWorker(OutboxRepository outbox, PaymentEventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean dispatchNext() {
        var candidate = outbox.lockNextDue();
        if (candidate.isEmpty()) return false;
        var event = candidate.get();
        try {
            publisher.publish(outbox.deserialize(event.payload()));
        } catch (RuntimeException exception) {
            // Propagate out through the transaction proxy first: SQL errors can leave PostgreSQL's
            // transaction aborted. Retry metadata must be written only in a fresh transaction.
            throw new OutboxPublishException(event.eventId(), event.attempts(), exception);
        }
        outbox.markDelivered(event.eventId());
        return true;
    }
}
