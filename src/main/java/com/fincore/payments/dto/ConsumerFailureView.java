package com.fincore.payments.dto;

import com.fincore.payments.domain.ConsumerFailureErrorCode;

import java.time.Instant;
import java.util.UUID;

// Operator output deliberately excludes the original record key, value, and exception text.
public record ConsumerFailureView(UUID failureId, String sourceName, String consumerName,
        String consumerGroup, String topic, int partition, long offset,
        ConsumerFailureErrorCode errorCode, int attempts, Instant firstFailedAt,
        Instant lastFailedAt, Instant resolvedAt, String wireFingerprint) {
}
