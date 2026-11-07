package com.fincore.payments.service;

import com.fincore.payments.dto.CreatePaymentRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class PaymentRequestFingerprint {
    private PaymentRequestFingerprint() { }

    public static String of(CreatePaymentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addField(digest, "create-payment:v1");
            // Preserve merchant reference exactly. Decimal scale and JSON layout are irrelevant.
            addField(digest, request.merchantReference());
            addField(digest, request.amount().stripTrailingZeros().toPlainString());
            // API/domain validation still requires uppercase currency; do not accept invalid input here.
            addField(digest, request.currency().toUpperCase(Locale.ROOT));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private static void addField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // A four-byte UTF-8 byte length makes boundaries unambiguous, even with delimiters in a field.
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
