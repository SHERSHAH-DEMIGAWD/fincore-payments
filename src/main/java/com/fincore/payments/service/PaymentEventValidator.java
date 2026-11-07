package com.fincore.payments.service;

import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.exception.InvalidPaymentEventException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PaymentEventValidator {
    public void validate(PaymentEvent event) {
        require(event != null, "Payment event is required.");
        require(event.eventId() != null, "Payment event ID is required.");
        require(event.eventType() != null, "Payment event type is required.");
        require(event.schemaVersion() == 1, "Payment event schema version is unsupported.");
        require(event.paymentId() != null, "Payment event payment ID is required.");
        require(event.paymentVersion() >= 0, "Payment event payment version must be nonnegative.");
        require(event.occurredAt() != null, "Payment event occurrence time is required.");

        PaymentResponse data = event.data();
        require(data != null, "Payment event snapshot is required.");
        require(event.paymentId().equals(data.id()), "Payment event snapshot payment ID must match.");
        require(data.status() != null && PaymentEventType.from(data.status()) == event.eventType(),
                "Payment event snapshot status must match its type.");
        require(data.merchantReference() != null && !data.merchantReference().isBlank()
                        && data.merchantReference().length() <= 255,
                "Payment event merchant reference must contain between 1 and 255 characters.");
        validateAmount(data.amount());
        require(data.currency() != null && data.currency().matches("[A-Z]{3}"),
                "Payment event currency must contain three uppercase letters.");
        require(data.createdAt() != null && data.updatedAt() != null,
                "Payment event snapshot timestamps are required.");
    }

    private void validateAmount(BigDecimal amount) {
        require(amount != null && amount.signum() > 0, "Payment event amount must be positive.");
        require((long) amount.precision() - amount.scale() <= 17,
                "Payment event amount must fit 19 digits with two decimal places.");
        require(amount.stripTrailingZeros().scale() <= 2,
                "Payment event amount must have at most two decimal places.");
        try {
            require(amount.setScale(2, RoundingMode.UNNECESSARY).precision() <= 19,
                    "Payment event amount must fit 19 digits with two decimal places.");
        } catch (ArithmeticException exception) {
            throw new InvalidPaymentEventException("Payment event amount must have at most two decimal places.");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new InvalidPaymentEventException(message);
    }
}
