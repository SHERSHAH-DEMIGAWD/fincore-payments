package com.fincore.payments.dto;
import java.util.UUID;
public record ConsumerRecoveryCommand(UUID requestId, UUID failureId, String expectedFingerprint,
        String operator, String reason) { }
