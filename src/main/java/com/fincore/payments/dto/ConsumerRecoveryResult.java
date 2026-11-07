package com.fincore.payments.dto;
import java.time.Instant;
import java.util.UUID;
public record ConsumerRecoveryResult(UUID requestId, UUID failureId, String action,
        Instant decidedAt, boolean replayed) { }
