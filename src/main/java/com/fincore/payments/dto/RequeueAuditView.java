package com.fincore.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record RequeueAuditView(UUID requestId, UUID eventId, long expectedGeneration,
        long resultingGeneration, int previousAttempts, int previousCycleAttempts,
        String operator, String reason, Instant requeuedAt) { }
