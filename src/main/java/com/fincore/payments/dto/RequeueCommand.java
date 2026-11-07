package com.fincore.payments.dto;

import java.util.UUID;

public record RequeueCommand(UUID requestId, UUID eventId, long expectedGeneration,
        String operator, String reason) { }
