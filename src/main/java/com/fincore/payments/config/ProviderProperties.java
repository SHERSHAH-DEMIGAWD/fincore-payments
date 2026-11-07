package com.fincore.payments.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.util.UUID;

@Validated
@ConfigurationProperties(prefix="fincore.provider")
public record ProviderProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:18081") URI baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("00000000-0000-0000-0000-000000000000") UUID instanceId,
        @DefaultValue("2000") @Min(100) @Max(10000) int timeoutMillis,
        @DefaultValue("30") @Min(5) @Max(120) int leaseSeconds) {
    @AssertTrue(message="Enabled sandbox provider requires a loopback URL, API key and nonzero instance UUID; lease must exceed request timeout.")
    public boolean isValidConfiguration() {
        if (leaseSeconds*1000L<timeoutMillis+2000L) return false;
        if (!enabled) return true;
        return baseUrl!=null && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                && baseUrl.getHost()!=null && java.util.Set.of("127.0.0.1","localhost","::1","[::1]").contains(baseUrl.getHost())
                && baseUrl.getUserInfo()==null && baseUrl.getQuery()==null && baseUrl.getFragment()==null
                && (baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"))
                && baseUrl.getPort()!=0 && baseUrl.getPort()<=65535
                && apiKey!=null && apiKey.matches("[!-~]{32,256}")
                && instanceId!=null && !instanceId.equals(new UUID(0,0));
    }
    @Override public String toString() { return "ProviderProperties[enabled="+enabled+",credentials=REDACTED]"; }
}
