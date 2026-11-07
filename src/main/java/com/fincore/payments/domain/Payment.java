package com.fincore.payments.domain;

import com.fincore.payments.exception.IllegalPaymentStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "merchant_reference", nullable = false, length = 255, updatable = false)
    private String merchantReference;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Payment() {
        // Required by JPA. Application code creates payments through create().
    }

    private Payment(UUID merchantId, String merchantReference, BigDecimal amount, String currency) {
        this.merchantId = java.util.Objects.requireNonNull(merchantId, "Merchant identity is required.");
        if (merchantReference == null || merchantReference.isBlank() || merchantReference.length() > 255) {
            throw new IllegalArgumentException("Merchant reference must contain 1 to 255 characters.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        // Milestone 1 uses two decimal places; never silently round a payment amount.
        BigDecimal normalizedAmount;
        try {
            normalizedAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount must have at most two decimal places.");
        }
        if (normalizedAmount.precision() > 19) {
            throw new IllegalArgumentException("Amount must have at most 17 integer digits.");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must contain exactly three uppercase letters.");
        }
        this.id = UUID.randomUUID();
        this.merchantReference = merchantReference;
        this.amount = normalizedAmount;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public static Payment create(String merchantReference, BigDecimal amount, String currency) {
        return new Payment(MerchantIdentity.LEGACY_ID, merchantReference, amount, currency);
    }

    public static Payment create(UUID merchantId, String merchantReference, BigDecimal amount, String currency) {
        return new Payment(merchantId, merchantReference, amount, currency);
    }

    public void authorize() {
        transitionTo(PaymentStatus.AUTHORIZED, status == PaymentStatus.CREATED);
    }

    public void capture() {
        transitionTo(PaymentStatus.CAPTURED, status == PaymentStatus.AUTHORIZED);
    }

    public void fail() {
        transitionTo(PaymentStatus.FAILED,
                status == PaymentStatus.CREATED || status == PaymentStatus.AUTHORIZED);
    }

    public void refund() {
        transitionTo(PaymentStatus.REFUNDED, status == PaymentStatus.CAPTURED);
    }

    /** Called only by the locked amount-operation transaction, with totals from immutable commands. */
    public void applyAmounts(BigDecimal captured, BigDecimal refunded) {
        if (!(status == PaymentStatus.AUTHORIZED || status == PaymentStatus.PARTIAL_CAPTURE
                || status == PaymentStatus.CAPTURED || status == PaymentStatus.PARTIAL_REFUND)
                || captured == null || refunded == null || captured.signum() <= 0
                || captured.compareTo(amount) > 0 || refunded.signum() < 0 || refunded.compareTo(captured) > 0)
            throw new IllegalArgumentException("Amount totals do not allow this payment transition.");
        status = amountStatus(amount,captured,refunded);
        // Consecutive partial operations can retain the same status. Always dirty this field,
        // even with a repeated/backward wall clock, so JPA advances the optimistic version.
        Instant observed = Instant.now();
        Instant minimum = updatedAt.plusNanos(1_000);
        updatedAt = observed.isAfter(minimum) ? observed : minimum;
    }

    public static PaymentStatus amountStatus(BigDecimal authorized, BigDecimal captured, BigDecimal refunded) {
        if (refunded.signum() > 0)
            return refunded.compareTo(captured) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIAL_REFUND;
        return captured.compareTo(authorized) == 0 ? PaymentStatus.CAPTURED : PaymentStatus.PARTIAL_CAPTURE;
    }

    private void transitionTo(PaymentStatus target, boolean allowed) {
        if (!allowed) {
            throw new IllegalPaymentStateTransitionException(status, target);
        }
        status = target;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getMerchantReference() { return merchantReference; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
