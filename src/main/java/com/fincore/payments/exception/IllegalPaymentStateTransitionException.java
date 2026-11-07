package com.fincore.payments.exception;

import com.fincore.payments.domain.PaymentStatus;

public class IllegalPaymentStateTransitionException extends RuntimeException {
    public IllegalPaymentStateTransitionException(PaymentStatus current, PaymentStatus target) {
        super("Cannot transition payment from " + current + " to " + target + ".");
    }
}
