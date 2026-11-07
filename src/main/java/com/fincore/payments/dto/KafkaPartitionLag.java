package com.fincore.payments.dto;

public record KafkaPartitionLag(
        int partition,
        Long endOffset,
        Long committedOffset,
        Long lag,
        KafkaLagStatus status) {
}
