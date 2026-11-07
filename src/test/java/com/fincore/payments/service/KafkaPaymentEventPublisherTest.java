package com.fincore.payments.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaPaymentEventPublisherTest {
    @Mock KafkaTemplate<String, String> kafka;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private KafkaPaymentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaPaymentEventPublisher(kafka, mapper,
                new KafkaEventProperties("payment-events.v1", "test-group", 15000));
    }

    @Test
    void sendsStableEnvelopeUsingPaymentIdAsPartitionKeyAndWaitsForAcknowledgement() throws Exception {
        PaymentEvent event = event();
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> acknowledgement = mock(CompletableFuture.class);
        when(kafka.send(eq("payment-events.v1"), eq(event.paymentId().toString()), anyString()))
                .thenReturn(acknowledgement);

        publisher.publish(event);

        verify(kafka).send("payment-events.v1", event.paymentId().toString(), mapper.writeValueAsString(event));
        verify(acknowledgement).get(15000, TimeUnit.MILLISECONDS);
    }

    @Test
    void brokerFailureCannotReturnSuccessfulPublication() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThatThrownBy(() -> publisher.publish(event())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka payment event publication was not acknowledged.");
    }

    @Test
    void acknowledgementTimeoutCannotReturnSuccessfulPublication() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> acknowledgement = mock(CompletableFuture.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledgement);
        when(acknowledgement.get(15000, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException());

        assertThatThrownBy(() -> publisher.publish(event())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka payment event publication was not acknowledged.");
    }

    @Test
    void interruptionIsRestoredForWorkerShutdown() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> acknowledgement = mock(CompletableFuture.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acknowledgement);
        when(acknowledgement.get(15000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> publisher.publish(event())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Kafka payment event publication was interrupted.");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    static PaymentEvent event() {
        UUID paymentId = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-21T10:00:00Z");
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1, paymentId, 0, time,
                new PaymentResponse(paymentId, "ORDER-KAFKA", new BigDecimal("10.00"), "INR",
                        PaymentStatus.CREATED, time, time));
    }
}
