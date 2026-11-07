package com.fincore.payments.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotBlank(message = "Merchant reference must not be blank.")
        @Size(max = 255, message = "Merchant reference must not exceed 255 characters.")
        String merchantReference,

        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "0", inclusive = false, message = "Amount must be greater than zero.")
        @Digits(integer = 17, fraction = 2, message = "Amount supports at most 17 integer digits and 2 decimal places.")
        BigDecimal amount,

        @NotBlank(message = "Currency is required.")
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must contain exactly three uppercase letters.")
        String currency
) {
}
