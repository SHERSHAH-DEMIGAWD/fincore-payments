package com.fincore.payments.dto;

import java.time.Instant;
import java.util.List;

public record KafkaLagReport(
        String topic,
        String consumerGroup,
        Instant observedAt,
        KafkaLagStatus status,
        Long totalLag,
        List<KafkaPartitionLag> partitions,
        String errorCode) {
    public KafkaLagReport {
        partitions = List.copyOf(partitions);
    }
}
