package com.fincore.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ProjectionApprovalLifecycle {
    private ProjectionApprovalLifecycle() { }
    public record RevocationCommand(@NotNull UUID requestId,@NotBlank @Size(max=500) String reason) { }
    public record Revocation(UUID requestId,UUID approvalId,Instant revokedAt,String actor,String reason,
            String assessment,boolean deletionPermitted) { }
    public record Result(Revocation revocation,boolean replayed) { }
}
