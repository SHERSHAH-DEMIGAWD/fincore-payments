package com.fincore.payments.service;

import com.fincore.payments.domain.ConsumerFailureErrorCode;
import com.fincore.payments.dto.ConsumerFailureView;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.exception.PaymentEventConflictException;
import com.fincore.payments.repository.ConsumerFailureRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConsumerFailureService {
    private final ConsumerFailureRepository failures;
    private final String consumerGroup;
    private final String sourceName;

    public ConsumerFailureService(ConsumerFailureRepository failures,
            @Value("${fincore.kafka.consumer-group:fincore-payment-projection-v1}") String consumerGroup,
            @Value("${fincore.kafka.source-name:primary}") String sourceName) {
        if (sourceName == null || sourceName.length() > 128 || !sourceName.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("Kafka source name must be a valid namespace of at most 128 characters.");
        }
        this.failures = failures;
        this.consumerGroup = consumerGroup;
        this.sourceName = sourceName;
    }

    // The failed receipt/projection transaction has already rolled back before diagnostics persist.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(ConsumerRecord<String, String> record, RuntimeException exception) {
        ConsumerFailureErrorCode code = exception instanceof InvalidPaymentEventException
                ? ConsumerFailureErrorCode.INVALID_EVENT
                : exception instanceof PaymentEventConflictException
                ? ConsumerFailureErrorCode.EVENT_CONFLICT : ConsumerFailureErrorCode.CONSUMER_FAILED;
        failures.record(sourceName, PaymentEventConsumer.CONSUMER_NAME, consumerGroup, record, code);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolved(ConsumerRecord<String, String> record) {
        failures.resolve(sourceName, PaymentEventConsumer.CONSUMER_NAME, record);
    }

    @Transactional(readOnly = true)
    public List<ConsumerFailureView> list(boolean openOnly, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Failure limit must be between 1 and 1000.");
        return failures.list(sourceName, PaymentEventConsumer.CONSUMER_NAME, openOnly, limit);
    }
}
