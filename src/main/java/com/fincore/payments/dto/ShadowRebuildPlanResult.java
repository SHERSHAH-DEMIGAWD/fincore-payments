package com.fincore.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record ShadowRebuildPlanResult(UUID runId, long generation, UUID requestId,
        String consumerName, Instant plannedAt, boolean replayed) { }
