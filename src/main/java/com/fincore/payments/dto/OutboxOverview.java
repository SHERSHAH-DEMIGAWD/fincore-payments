package com.fincore.payments.dto;

import java.time.Instant;

public record OutboxOverview(Instant observedAt, long pending, long delivered, long dead,
        long ready, long blocked, long backedOff, Instant oldestPendingAt, Instant oldestReadyAt) { }
