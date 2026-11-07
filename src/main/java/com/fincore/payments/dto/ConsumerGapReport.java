package com.fincore.payments.dto;
import java.time.Instant;
public record ConsumerGapReport(String sourceName, String consumerName, String topic,
        Instant observedAt, String status, long openQuarantines, long otherOpenFailures) { }
