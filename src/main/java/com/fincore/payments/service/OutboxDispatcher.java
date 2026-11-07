package com.fincore.payments.service;

import com.fincore.payments.config.OutboxProperties;
import com.fincore.payments.exception.OutboxPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxDeliveryWorker worker;
    private final OutboxFailureRecorder failures;
    private final OutboxProperties properties;

    public OutboxDispatcher(OutboxDeliveryWorker worker, OutboxFailureRecorder failures, OutboxProperties properties) {
        this.worker = worker;
        this.failures = failures;
        this.properties = properties;
    }

    public int dispatchBatch() {
        int delivered = 0;
        for (int attempt = 0; attempt < properties.batchSize(); attempt++) {
            try {
                if (!worker.dispatchNext()) break;
                delivered++;
            } catch (OutboxPublishException exception) {
                failures.recordFailure(exception.eventId(), exception.expectedAttempts());
                log.debug("Payment event delivery failed; retry policy applied for event {}", exception.eventId());
            }
        }
        return delivered;
    }
}
