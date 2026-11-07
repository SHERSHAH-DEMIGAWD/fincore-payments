package com.fincore.payments.dto;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A review observation, never an authorization to delete retained evidence. */
public record ProjectionEvidenceReport(Instant observedAt, String sourceName, String topic, long routeRevision,
        int reviewAfterDays, String assessment, List<String> preservationRequirements,
        List<TargetEvidence> targets, boolean hasMore, UUID nextAfterRunId) {
    public record TargetEvidence(UUID runId, String consumerName, long buildGeneration, String lifecycle,
            Instant createdAt, Instant retiredAt, Long retirementRevision, String reviewState,
            boolean deletionPermitted, ArtifactInventory artifacts) { }

    public record ArtifactInventory(long generations, long manifestItems, long receipts, long projections,
            long routingDecisions, BigInteger retainedTextBytes) { }
}
