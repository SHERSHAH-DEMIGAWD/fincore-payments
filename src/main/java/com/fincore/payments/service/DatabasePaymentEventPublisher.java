package com.fincore.payments.service;

import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.repository.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "fincore.events", name = "transport", havingValue = "local", matchIfMissing = true)
public class DatabasePaymentEventPublisher implements PaymentEventPublisher {
    private final OutboxRepository outbox;

    public DatabasePaymentEventPublisher(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(PaymentEvent event) {
        outbox.deliverLocally(event);
    }
}
