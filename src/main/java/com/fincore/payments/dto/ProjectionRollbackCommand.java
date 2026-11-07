package com.fincore.payments.dto;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fincore.payments.config.StrictRoutingCounterDeserializer;
import java.util.UUID;

public record ProjectionRollbackCommand(@NotNull UUID requestId,
        @JsonProperty(required=true) @JsonDeserialize(using=StrictRoutingCounterDeserializer.class)
        @PositiveOrZero long expectedRevision,
        @NotBlank @Size(max = 500) String reason) { }
