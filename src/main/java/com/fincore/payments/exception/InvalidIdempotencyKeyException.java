package com.fincore.payments.exception;

import com.fincore.payments.service.IdempotencyKey;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super(IdempotencyKey.VALIDATION_MESSAGE);
    }
}
