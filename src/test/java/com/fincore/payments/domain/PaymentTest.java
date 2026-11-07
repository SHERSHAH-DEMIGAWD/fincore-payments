package com.fincore.payments.domain;

import com.fincore.payments.exception.IllegalPaymentStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {
    @Test
    void repeatedPartialStatusStillAdvancesTimestampWhenWallClockIsBehind() {
        Payment payment=paymentInState(PaymentStatus.PARTIAL_CAPTURE);
        var previous=java.time.Instant.now().plusSeconds(60);
        org.springframework.test.util.ReflectionTestUtils.setField(payment,"updatedAt",previous);
        payment.applyAmounts(new BigDecimal("60.00"),BigDecimal.ZERO);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CAPTURE);
        assertThat(payment.getUpdatedAt()).isAfter(previous);
    }
    @Test
    void createsPaymentWithExactMoneyAndInitialState() {
        Payment payment = Payment.create("ORDER-123", new BigDecimal("1499.99"), "INR");

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getMerchantReference()).isEqualTo("ORDER-123");
        assertThat(payment.getAmount()).isEqualTo(new BigDecimal("1499.99"));
        assertThat(payment.getCurrency()).isEqualTo("INR");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getCreatedAt()).isNotNull();
        assertThat(payment.getUpdatedAt()).isEqualTo(payment.getCreatedAt());
        assertThat(payment.getVersion()).isNull(); // Hibernate initializes this on insertion.
    }

    @ParameterizedTest(name = "{0} -> {1}: allowed={3}")
    @MethodSource("transitions")
    void enforcesEveryTransition(PaymentStatus initial, PaymentStatus target,
            Consumer<Payment> operation, boolean allowed) {
        Payment payment = paymentInState(initial);
        var previousUpdate = payment.getUpdatedAt();
        var createdAt = payment.getCreatedAt();

        if (allowed) {
            operation.accept(payment);
            assertThat(payment.getStatus()).isEqualTo(target);
            assertThat(payment.getUpdatedAt()).isAfterOrEqualTo(previousUpdate);
            assertThat(payment.getCreatedAt()).isEqualTo(createdAt);
        } else {
            assertThatThrownBy(() -> operation.accept(payment))
                    .isInstanceOf(IllegalPaymentStateTransitionException.class)
                    .hasMessageContaining(initial.name()).hasMessageContaining(target.name());
            assertThat(payment.getStatus()).isEqualTo(initial);
            assertThat(payment.getUpdatedAt()).isEqualTo(previousUpdate);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "1.001", "100000000000000000.00"})
    void rejectsInvalidAmounts(String amount) {
        assertThatThrownBy(() -> Payment.create("ORDER-123", new BigDecimal(amount), "INR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> Payment.create("ORDER-123", null, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000", "1000.0", "1000.00"})
    void storesAmountsAtTwoDecimalPlaces(String amount) {
        assertThat(Payment.create("ORDER-123", new BigDecimal(amount), "INR").getAmount())
                .isEqualTo(new BigDecimal("1000.00"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankMerchantReferences(String reference) {
        assertThatThrownBy(() -> Payment.create(reference, BigDecimal.ONE, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedMerchantReference() {
        assertThatThrownBy(() -> Payment.create("a".repeat(256), BigDecimal.ONE, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "inr", "IN", "INRR", "I1R", " INR"})
    void rejectsInvalidCurrency(String currency) {
        assertThatThrownBy(() -> Payment.create("ORDER-123", BigDecimal.ONE, currency))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> transitions() {
        return Arrays.stream(PaymentStatus.values()).flatMap(initial -> Stream.of(
                Arguments.of(initial, PaymentStatus.AUTHORIZED, (Consumer<Payment>) Payment::authorize,
                        initial == PaymentStatus.CREATED),
                Arguments.of(initial, PaymentStatus.CAPTURED, (Consumer<Payment>) Payment::capture,
                        initial == PaymentStatus.AUTHORIZED),
                Arguments.of(initial, PaymentStatus.FAILED, (Consumer<Payment>) Payment::fail,
                        initial == PaymentStatus.CREATED || initial == PaymentStatus.AUTHORIZED),
                Arguments.of(initial, PaymentStatus.REFUNDED, (Consumer<Payment>) Payment::refund,
                        initial == PaymentStatus.CAPTURED)));
    }

    private static Payment paymentInState(PaymentStatus status) {
        Payment payment = Payment.create("ORDER-123", new BigDecimal("100.00"), "INR");
        switch (status) {
            case CREATED -> { }
            case AUTHORIZED -> payment.authorize();
            case CAPTURED -> { payment.authorize(); payment.capture(); }
            case PARTIAL_CAPTURE -> { payment.authorize(); payment.applyAmounts(new BigDecimal("50.00"),BigDecimal.ZERO); }
            case PARTIAL_REFUND -> { payment.authorize(); payment.applyAmounts(new BigDecimal("50.00"),new BigDecimal("10.00")); }
            case FAILED -> payment.fail();
            case REFUNDED -> { payment.authorize(); payment.capture(); payment.refund(); }
        }
        return payment;
    }
}
