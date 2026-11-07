package com.fincore.payments.dto;

/** Internal use-case result; the API exposes only the payment response. */
public record CreatePaymentResult(PaymentResponse payment, boolean created) {
}
