package com.fincore.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.domain.PaymentEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(prefix = "fincore.events", name = "transport", havingValue = "kafka")
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final KafkaEventProperties properties;

    public KafkaPaymentEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                      KafkaEventProperties properties) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void publish(PaymentEvent event) {
        try {
            // Returning a future immediately would allow the outbox to claim success before Kafka does.
            kafka.send(properties.topic(), event.paymentId().toString(), mapper.writeValueAsString(event))
                    .get(properties.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka payment event publication was interrupted.", exception);
        } catch (JsonProcessingException | ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Kafka payment event publication was not acknowledged.", exception);
        }
    }
}
