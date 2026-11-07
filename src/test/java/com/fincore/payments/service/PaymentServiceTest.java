package com.fincore.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.exception.IllegalPaymentStateTransitionException;
import com.fincore.payments.exception.PaymentNotFoundException;
import com.fincore.payments.exception.IdempotencyConflictException;
import com.fincore.payments.exception.InvalidIdempotencyKeyException;
import com.fincore.payments.repository.IdempotencyRepository;
import com.fincore.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock
    private PaymentRepository repository;
    @Mock
    private IdempotencyRepository idempotencyRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private OutboxService outboxService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(repository, idempotencyRepository, objectMapper, ledgerService, outboxService);
    }

    @Test
    void createsAndPersistsPayment() {
        when(idempotencyRepository.claim(anyString(), anyString(), any(), anyString(), any())).thenReturn(true);
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.createPayment("test-key",
                new CreatePaymentRequest("ORDER-123", new BigDecimal("1499.99"), "INR"));
        var response = result.payment();
        assertThat(result.created()).isTrue();

        var captor = ArgumentCaptor.forClass(Payment.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(response.id()).isEqualTo(captor.getValue().getId());
        assertThat(response.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(response.amount()).isEqualTo(new BigDecimal("1499.99"));
        verify(outboxService).record(captor.getValue());
    }

    @Test
    void replaysStoredSnapshotWithoutSavingPayment() throws Exception {
        var request = new CreatePaymentRequest("ORDER-123", new BigDecimal("1499.99"), "INR");
        var snapshot = com.fincore.payments.dto.PaymentResponse.from(
                Payment.create(request.merchantReference(), request.amount(), request.currency()));
        when(idempotencyRepository.find("test-key")).thenReturn(new IdempotencyRepository.StoredResult(
                PaymentRequestFingerprint.of(request), objectMapper.writeValueAsString(snapshot)));
        var result = service.createPayment("test-key", request);
        assertThat(result.created()).isFalse();
        assertThat(result.payment()).isEqualTo(snapshot);
        verifyNoInteractions(repository);
        verifyNoInteractions(outboxService);
    }

    @Test
    void rejectsDifferentPayloadWithoutSavingPayment() {
        var request = new CreatePaymentRequest("ORDER-123", BigDecimal.TEN, "INR");
        when(idempotencyRepository.find("test-key"))
                .thenReturn(new IdempotencyRepository.StoredResult("different-fingerprint", "{}"));
        assertThatThrownBy(() -> service.createPayment("test-key", request))
                .isInstanceOf(IdempotencyConflictException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidKeyBeforeAccessingDatabase() {
        var request = new CreatePaymentRequest("ORDER-123", BigDecimal.TEN, "INR");
        for (String key : new String[]{null, "", " ", "with space", "a".repeat(256), "clé"}) {
            assertThatThrownBy(() -> service.createPayment(key, request))
                    .isInstanceOf(InvalidIdempotencyKeyException.class);
        }
        verifyNoInteractions(repository, idempotencyRepository);
    }

    @Test
    void rejectsUnsupportedPrecisionBeforeClaimingKey() {
        var request = new CreatePaymentRequest("ORDER-123", new BigDecimal("10.001"), "INR");
        assertThatThrownBy(() -> service.createPayment("test-key", request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, idempotencyRepository);
    }

    @Test
    void readsPaymentWithoutWriting() {
        Payment payment = existingPayment();
        assertThat(service.getPayment(payment.getId()).id()).isEqualTo(payment.getId());
        verify(repository, never()).flush();
    }

    @Test
    void runsCompletePaymentLifecycle() {
        Payment payment = existingPayment();
        assertThat(service.authorizePayment(payment.getId()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(service.capturePayment(payment.getId()).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(service.refundPayment(payment.getId()).status()).isEqualTo(PaymentStatus.REFUNDED);
        verify(ledgerService).postCapture(payment);
        verify(ledgerService).postRefund(payment);
    }

    @Test
    void failsCreatedPayment() {
        Payment payment = existingPayment();
        assertThat(service.failPayment(payment.getId()).status()).isEqualTo(PaymentStatus.FAILED);
        verify(repository).flush();
    }

    @Test
    void failsAuthorizedPayment() {
        Payment payment = existingPayment();
        payment.authorize();
        assertThat(service.failPayment(payment.getId()).status()).isEqualTo(PaymentStatus.FAILED);
        verify(repository).flush();
    }

    @Test
    void illegalTransitionDoesNotFlush() {
        Payment payment = existingPayment();
        assertThatThrownBy(() -> service.refundPayment(payment.getId()))
                .isInstanceOf(IllegalPaymentStateTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        verify(repository, never()).flush();
        verifyNoInteractions(ledgerService);
    }

    @Test
    void reportsMissingPayment() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPayment(id)).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.authorizePayment(id)).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.capturePayment(id)).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.failPayment(id)).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.refundPayment(id)).isInstanceOf(PaymentNotFoundException.class);
        verify(repository, never()).flush();
    }

    @Test
    void propagatesOptimisticLockConflictForCentralErrorHandling() {
        Payment payment = existingPayment();
        doThrow(new ObjectOptimisticLockingFailureException(Payment.class, payment.getId()))
                .when(repository).flush();
        assertThatThrownBy(() -> service.authorizePayment(payment.getId()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private Payment existingPayment() {
        Payment payment = Payment.create("ORDER-123", new BigDecimal("100.00"), "INR");
        when(repository.findById(payment.getId())).thenReturn(Optional.of(payment));
        return payment;
    }
}
