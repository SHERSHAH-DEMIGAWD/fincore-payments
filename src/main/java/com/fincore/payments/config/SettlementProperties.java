package com.fincore.payments.config;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.util.UUID;
@Validated
@ConfigurationProperties(prefix="fincore.settlement")
public record SettlementProperties(@DefaultValue("false") boolean enabled,@DefaultValue("") String signingKey,
        @DefaultValue("sandbox-v1") String keyId,@DefaultValue("00000000-0000-0000-0000-000000000000") UUID instanceId) {
    @AssertTrue(message="Enabled settlement imports require a signing key, key ID and sandbox instance UUID.")
    public boolean isValidConfiguration(){return !enabled || (signingKey!=null && signingKey.matches("[!-~]{32,256}")
            && keyId!=null && keyId.matches("[A-Za-z0-9_-]{1,64}") && instanceId!=null && !instanceId.equals(new UUID(0,0)));}
    @Override public String toString(){return "SettlementProperties[enabled="+enabled+",credentials=REDACTED]";}
}
