package com.fincore.payments.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fincore.kafka")
public record KafkaEventProperties(
        @DefaultValue("payment-events.v1") @NotBlank @Size(max = 249)
        @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]*") String topic,
        @DefaultValue("fincore-payment-projection-v1") @NotBlank String consumerGroup,
        @DefaultValue("15000") @Min(1000) @Max(60000) long sendTimeoutMs) {
}
