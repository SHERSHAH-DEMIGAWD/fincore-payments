package com.fincore.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectionRoutingResult(UUID requestId, String action, long revision, String activeConsumer,
        String actor, String reason, Instant occurredAt, boolean replayed) { }
