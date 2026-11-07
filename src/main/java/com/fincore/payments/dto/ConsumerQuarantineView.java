package com.fincore.payments.dto;
import java.time.Instant;
import java.util.UUID;
public record ConsumerQuarantineView(UUID failureId, String sourceName, String consumerName,
        String topic, int partition, long offset, String fingerprint, UUID quarantineRequestId,
        String operator, String reason, Instant quarantinedAt, UUID replayRequestId, Instant replayedAt) { }
