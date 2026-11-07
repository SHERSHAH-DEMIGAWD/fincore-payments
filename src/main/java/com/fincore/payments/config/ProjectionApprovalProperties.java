package com.fincore.payments.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="fincore.projection-approvals")
public record ProjectionApprovalProperties(@DefaultValue("60") @Min(1) @Max(1440) int validityMinutes) { }
