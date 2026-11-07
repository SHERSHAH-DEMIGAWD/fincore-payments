package com.fincore.payments.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.util.UUID;

@Validated
@ConfigurationProperties(prefix="fincore.provider-webhook")
public record ProviderWebhookProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String signingKey,@DefaultValue("sandbox-v1") String keyId,
        @DefaultValue("00000000-0000-0000-0000-000000000000") UUID instanceId,
        @DefaultValue("300") @Min(30) @Max(900) int toleranceSeconds) {
    @AssertTrue(message="Enabled provider webhooks require a signing key, key ID and nonzero sandbox instance UUID.")
    public boolean isValidConfiguration() {
        return !enabled || (signingKey!=null && signingKey.matches("[!-~]{32,256}")
                && keyId!=null && keyId.matches("[A-Za-z0-9_-]{1,64}")
                && instanceId!=null && !instanceId.equals(new UUID(0,0)));
    }
    @Override public String toString() { return "ProviderWebhookProperties[enabled="+enabled+",credentials=REDACTED]"; }
}
