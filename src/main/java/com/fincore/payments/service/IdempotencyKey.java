package com.fincore.payments.service;

import com.fincore.payments.exception.InvalidIdempotencyKeyException;

public final class IdempotencyKey {
    public static final String HEADER_NAME = "Idempotency-Key";
    public static final int MAX_LENGTH = 255;
    public static final String FORMAT = "[!-~]{1," + MAX_LENGTH + "}";
    public static final String VALIDATION_MESSAGE =
            "Idempotency-Key must contain 1 to 255 visible ASCII characters without whitespace.";

    private IdempotencyKey() { }

    public static void validate(String key) {
        if (key == null || !key.matches(FORMAT)) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
