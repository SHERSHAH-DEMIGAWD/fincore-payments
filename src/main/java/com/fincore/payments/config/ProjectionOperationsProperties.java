package com.fincore.payments.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="fincore.projection-operations")
public record ProjectionOperationsProperties(
        @DefaultValue("30") @Min(1) @Max(3650) int reviewAfterDays,
        @DefaultValue("100000") @Min(1) @Max(1000000) int maxComparisonRows) { }
