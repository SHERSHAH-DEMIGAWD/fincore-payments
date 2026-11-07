package com.fincore.payments.service;

import com.fincore.payments.repository.ConsumerFailureRepository;
import com.fincore.payments.repository.ConsumerRecoveryRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes live processing and explicit recovery before allowing a Kafka acknowledgement. */
@Service
public class ConsumerRecordProcessor {
    private final PaymentEventRecordDecoder decoder;
    private final PaymentEventConsumer consumer;
    private final ConsumerFailureRepository failures;
    private final ConsumerRecoveryRepository recovery;
    private final String sourceName;

    public ConsumerRecordProcessor(PaymentEventRecordDecoder decoder, PaymentEventConsumer consumer,
            ConsumerFailureRepository failures, ConsumerRecoveryRepository recovery,
            @Value("${fincore.kafka.source-name:primary}") String sourceName) {
        this.decoder = decoder;
        this.consumer = consumer;
        this.failures = failures;
        this.recovery = recovery;
        this.sourceName = sourceName;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void process(ConsumerRecord<String, String> record) {
        failures.lockPosition(sourceName, PaymentEventConsumer.CONSUMER_NAME, record);
        // The common lock also verifies the immutable fingerprint. A quarantine is a durable
        // disposition, not successful processing: retain its gap until an audited replay commits.
        if (recovery.isQuarantined(sourceName, PaymentEventConsumer.CONSUMER_NAME, record)) return;
        consumer.consume(decoder.decode(record));
        // REQUIRED consumer work and position resolution join this transaction. A resolution
        // failure now rolls back receipt/projection too. Offset acknowledgement follows commit.
        failures.resolve(sourceName, PaymentEventConsumer.CONSUMER_NAME, record);
    }
}
