package com.fincore.payments.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A frozen evidence inventory, never a deletion capability. No original payloads are exported. */
public record ProjectionPurgePlan(UUID requestId, int planVersion, UUID runId, String sourceName, String topic,
        long buildGeneration, long expectedRevision, long retirementRevision, Instant retiredAt,
        Instant observedAt, int reviewAfterDays, String actor, String reason,
        String assessment, boolean deletionPermitted, List<String> preservationRequirements,
        List<Artifact> artifacts) {
    public record Artifact(String kind, String identity, String sha256) { }
    public record Result(ProjectionPurgePlan plan, boolean replayed) { }
    public record Review(UUID requestId, Instant observedAt, String assessment, boolean deletionPermitted,
            List<String> blockers, List<String> preservationRequirements) { }
}
