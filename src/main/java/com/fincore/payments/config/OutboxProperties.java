package com.fincore.payments.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fincore.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1000") @Min(1) @Max(3600000) long fixedDelayMs,
        @DefaultValue("50") @Min(1) @Max(1000) int batchSize,
        @DefaultValue("5") @Min(1) @Max(100) int maxAttempts,
        @DefaultValue("1000") @Min(1) @Max(3600000) long initialBackoffMs,
        @DefaultValue("60000") @Min(1) @Max(3600000) long maxBackoffMs) {
    public OutboxProperties {
        if (initialBackoffMs > maxBackoffMs) {
            throw new IllegalArgumentException("Initial outbox backoff must not exceed maximum backoff.");
        }
    }

    public long retryDelayMs(int failedAttempt) {
        long delay = initialBackoffMs;
        for (int attempt = 1; attempt < failedAttempt && delay < maxBackoffMs; attempt++) {
            delay = Math.min(delay * 2, maxBackoffMs);
        }
        return delay;
    }
}
