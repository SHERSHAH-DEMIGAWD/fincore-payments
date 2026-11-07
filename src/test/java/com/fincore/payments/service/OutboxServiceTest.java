package com.fincore.payments.service;

import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.repository.OutboxRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {
    @Mock OutboxRepository repository;
    @Mock PaymentRepository payments;
    OutboxService service;

    @BeforeEach
    void setUp() {
        service = new OutboxService(repository, payments);
    }

    @Test
    void recordsImmutableVersionedSnapshot() {
        var payment = Payment.create("ORDER-1", BigDecimal.TEN, "INR");
        // Represents the version assigned by JPA flush; real database tests verify that boundary.
        ReflectionTestUtils.setField(payment, "version", 0L);
        service.record(payment);
        var eventArgument = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(repository).save(eventArgument.capture());
        var event = eventArgument.getValue();
        payment.authorize();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo(PaymentEventType.PAYMENT_CREATED);
        assertThat(event.paymentVersion()).isZero();
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.occurredAt().getNano() % 1000).isZero();
        assertThat(event.data().status().name()).isEqualTo("CREATED");
    }

    @Test
    void refusesEventBeforePaymentFlush() {
        var payment = Payment.create("ORDER-1", BigDecimal.TEN, "INR");
        assertThatThrownBy(() -> service.record(payment)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Flush payment");
        verifyNoInteractions(repository);
    }

    @Test
    void nonexistentPaymentDoesNotReturnEventHistory() {
        assertThatThrownBy(() -> service.getPaymentEvents(UUID.randomUUID()))
                .isInstanceOf(PaymentNotFoundException.class);
        verifyNoInteractions(repository);
    }
}
