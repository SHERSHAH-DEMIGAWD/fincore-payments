package com.fincore.payments.config;

import com.fincore.payments.merchantwebhook.MerchantWebhookDestinationPolicy;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@Validated
@ConfigurationProperties(prefix="fincore.merchant-webhooks")
public record MerchantWebhookProperties(@DefaultValue("false") boolean enabled,@DefaultValue("false") boolean dispatchEnabled,
        @DefaultValue("") String masterKey,@DefaultValue("local-master-v1") String masterKeyId,
        @DefaultValue List<String> allowedDestinations,@DefaultValue("5") @Min(1) @Max(20) int maxSubscriptions,
        @DefaultValue("20") @Min(1) @Max(100) int batchSize,@DefaultValue("2000") @Min(100) @Max(10000) int timeoutMillis,
        @DefaultValue("30") @Min(5) @Max(120) int leaseSeconds,@DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("1000") @Min(1) @Max(3600000) long initialBackoffMillis,
        @DefaultValue("60000") @Min(1) @Max(3600000) long maxBackoffMillis) {
    @AssertTrue(message="Enabled merchant webhooks require a valid master key/key ID and approved literal-loopback destinations; lease and retry bounds must agree.")
    public boolean isValidConfiguration() {
        if (dispatchEnabled && !enabled || leaseSeconds*1000L<timeoutMillis+2000L || maxBackoffMillis<initialBackoffMillis) return false;
        if (!enabled) return true;
        try {
            if (masterKey==null || Base64.getDecoder().decode(masterKey).length!=32
                    || !Base64.getEncoder().encodeToString(Base64.getDecoder().decode(masterKey)).equals(masterKey) || masterKeyId==null
                    || !masterKeyId.matches("[A-Za-z0-9_-]{1,64}") || allowedDestinations==null
                    || allowedDestinations.isEmpty() || allowedDestinations.size()>20) return false;
            return allowedDestinations.stream().allMatch(url -> url.equals(MerchantWebhookDestinationPolicy.canonical(url)))
                    && new HashSet<>(allowedDestinations).size()==allowedDestinations.size();
        } catch (IllegalArgumentException invalid) { return false; }
    }
    @Override public String toString() { return "MerchantWebhookProperties[enabled="+enabled+",dispatchEnabled="+dispatchEnabled+",credentials=REDACTED]"; }
}
