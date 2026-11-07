package com.fincore.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record RequeueResult(UUID requestId, UUID eventId, long generation,
        Instant requeuedAt, boolean replayed) { }
