package com.fincore.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fincore.payments.config.StrictRoutingCounterDeserializer;
import com.fincore.payments.config.StrictAcknowledgementDeserializer;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectionPreservation(UUID requestId,UUID planId,int formatVersion,long expectedRevision,
        Instant preservedAt,String actor,String reason,int artifactCount,long exportBytes,String sha256,
        String assessment,boolean deletionPermitted) {
    public record Command(@NotNull UUID requestId,
            @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class)
            @PositiveOrZero long expectedRevision,@NotBlank @Size(max=500) String reason) { }
    public record ApprovalCommand(@NotNull UUID requestId,@NotNull UUID preservationId,
            @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class)
            @PositiveOrZero long expectedRevision,
            @NotNull @Pattern(regexp="[0-9a-f]{64}") String exportSha256,
            @JsonDeserialize(using=StrictAcknowledgementDeserializer.class)
            @NotNull @AssertTrue Boolean preservationAcknowledged,@NotBlank @Size(max=500) String reason) { }
    public record ExportRecord(String kind,String identity,String sha256,String body) { }
    public record Bundle(int formatVersion,UUID preservationId,ProjectionPurgePlan plan,Instant preservedAt,
            String actor,String reason,List<ExportRecord> records) { }
    public record Result(ProjectionPreservation preservation,boolean replayed) { }
    public record Approval(UUID requestId,UUID planId,UUID preservationId,long expectedRevision,
            String exportSha256,boolean preservationAcknowledged,Instant approvedAt,String actor,String reason,
            String assessment,boolean deletionPermitted,Integer validityMinutes,Instant expiresAt) { }
    public record ApprovalResult(Approval approval,boolean replayed) { }
    public record ApprovalReview(UUID requestId,Instant observedAt,String assessment,boolean deletionPermitted,
            List<String> blockers) { }
    public record Download(UUID preservationId,String sha256,byte[] bytes) { }
}
