package com.fincore.payments.service;

import com.fincore.payments.config.OutboxProperties;
import com.fincore.payments.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OutboxFailureRecorder {
    private final OutboxRepository outbox;
    private final OutboxProperties properties;

    public OutboxFailureRecorder(OutboxRepository outbox, OutboxProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(UUID eventId, int expectedAttempts) {
        return outbox.failureCycleAttempts(eventId, expectedAttempts)
                .map(cycleAttempts -> outbox.recordFailure(eventId, expectedAttempts, properties.maxAttempts(),
                        properties.retryDelayMs(cycleAttempts + 1)))
                .orElse(false);
    }
}
