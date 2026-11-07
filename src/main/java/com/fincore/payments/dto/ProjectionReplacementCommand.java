package com.fincore.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fincore.payments.config.StrictRoutingCounterDeserializer;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record ProjectionReplacementCommand(@NotNull UUID requestId, @NotNull UUID expectedPreviousRunId,
        @NotNull UUID runId,
        @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class)
        @PositiveOrZero long expectedBuildGeneration,
        @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class)
        @PositiveOrZero long expectedRevision,
        @NotBlank @Size(max=500) String reason) { }
