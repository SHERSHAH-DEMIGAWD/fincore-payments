package com.fincore.payments.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "fincore.events", name = "transport", havingValue = "kafka")
public class KafkaPaymentEventListener {
    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentEventListener.class);
    private final ConsumerRecordProcessor processor;
    private final ConsumerFailureService failures;

    public KafkaPaymentEventListener(ConsumerRecordProcessor processor, ConsumerFailureService failures) {
        this.processor = processor;
        this.failures = failures;
    }

    @KafkaListener(id = "payment-projection", topics = "${fincore.kafka.topic:payment-events.v1}",
            groupId = "${fincore.kafka.consumer-group:fincore-payment-projection-v1}",
            autoStartup = "${fincore.kafka.listener-auto-startup:true}",
            containerFactory = "paymentEventKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            // The proxy commits processing/resolution or verifies durable quarantine before
            // returning. AckMode.RECORD advances the offset only after this return.
            processor.process(record);
        } catch (RuntimeException original) {
            try {
                failures.failed(record, original);
            } catch (RuntimeException recordingFailure) {
                log.warn("Could not record Kafka consumer failure; failure type: {}",
                        recordingFailure.getClass().getSimpleName());
            }
            throw original;
        }
    }
}
