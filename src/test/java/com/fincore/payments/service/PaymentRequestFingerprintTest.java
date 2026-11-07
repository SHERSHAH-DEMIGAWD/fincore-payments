package com.fincore.payments.service;

import com.fincore.payments.dto.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestFingerprintTest {
    @ParameterizedTest
    @ValueSource(strings = {"1000", "1000.0", "1000.00", "1E+3"})
    void equivalentAmountsHaveIdenticalFingerprints(String amount) {
        assertThat(hash("ORDER-123", amount, "INR")).isEqualTo(hash("ORDER-123", "1000.00", "INR"));
    }

    @Test
    void deterministicSha256WithFixedFieldFraming() {
        String fingerprint = hash("ORDER-123", "10.00", "INR");
        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(hash("ORDER-123", "10.00", "INR")).isEqualTo(fingerprint);
        assertThat(hash("ORDER-123", "10.00", "inr")).isEqualTo(fingerprint);
    }

    @Test
    void everyLogicalFieldAffectsFingerprint() {
        String original = hash("ORDER-123", "10", "INR");
        assertThat(hash("ORDER-124", "10", "INR")).isNotEqualTo(original);
        assertThat(hash("ORDER-123", "11", "INR")).isNotEqualTo(original);
        assertThat(hash("ORDER-123", "10", "USD")).isNotEqualTo(original);
    }

    @Test
    void preservesMerchantReferenceWhitespaceCaseAndDelimiters() {
        String original = hash("ORDER-123", "10", "INR");
        assertThat(hash(" ORDER-123", "10", "INR")).isNotEqualTo(original);
        assertThat(hash("order-123", "10", "INR")).isNotEqualTo(original);
        assertThat(hash("ORDER|123:é\n", "10", "INR"))
                .isNotEqualTo(hash("ORDER|123:é", "10", "INR"));
        // Without length framing these two reference/amount combinations would concatenate identically.
        assertThat(hash("ORDER1", "23", "INR")).isNotEqualTo(hash("ORDER12", "3", "INR"));
    }

    private String hash(String reference, String amount, String currency) {
        return PaymentRequestFingerprint.of(new CreatePaymentRequest(reference, new BigDecimal(amount), currency));
    }
}
