package com.fincore.payments.provider;

import com.fincore.payments.config.ProviderWebhookProperties;
import com.fincore.payments.dto.ProviderWebhook.Verified;
import com.fincore.payments.exception.ProviderWebhookException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;

@Component
public class ProviderWebhookVerifier {
    private final ProviderWebhookProperties properties;
    private final Clock clock;
    @Autowired public ProviderWebhookVerifier(ProviderWebhookProperties properties) { this(properties,Clock.systemUTC()); }
    ProviderWebhookVerifier(ProviderWebhookProperties properties,Clock clock) { this.properties=properties; this.clock=clock; }
    public Verified verify(HttpServletRequest request) {
        if (!properties.enabled()) throw new ProviderWebhookException(503,"WEBHOOK_UNAVAILABLE");
        String timestamp=single(request,ProviderWebhookWire.TIMESTAMP),keyId=single(request,ProviderWebhookWire.KEY_ID),signature=single(request,ProviderWebhookWire.SIGNATURE);
        if (!timestamp.matches("[1-9][0-9]{0,11}") || !keyId.equals(properties.keyId()) || !signature.matches("v1=[0-9a-f]{64}")) unauthorized();
        long signedAt=Long.parseLong(timestamp);
        fresh(signedAt);
        byte[] body;
        try { body=request.getInputStream().readNBytes(ProviderWire.MAX_BYTES+1); }
        catch (java.io.IOException unreadable) { throw new ProviderWebhookException(400,"INVALID_WEBHOOK"); }
        if (body.length>ProviderWire.MAX_BYTES) throw new ProviderWebhookException(413,"WEBHOOK_TOO_LARGE");
        String expected=ProviderWebhookWire.signature(properties.signingKey(),keyId,timestamp,body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII))) unauthorized();
        fresh(signedAt);
        try {
            var event=ProviderWebhookWire.read(body);
            if (!properties.instanceId().equals(event.result().instanceId())) unauthorized();
            return new Verified(event,body,keyId,signedAt);
        } catch (ProviderWebhookException refused) { throw refused; }
        catch (java.io.IOException | IllegalArgumentException malformed) { throw new ProviderWebhookException(400,"INVALID_WEBHOOK"); }
    }
    private static String single(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if (values.size()!=1) { unauthorized(); return ""; }
        return values.getFirst();
    }
    private void fresh(long signedAt) {
        long now=clock.instant().getEpochSecond();
        if (signedAt<now-properties.toleranceSeconds() || signedAt>now+properties.toleranceSeconds()) unauthorized();
    }
    private static void unauthorized() { throw new ProviderWebhookException(401,"WEBHOOK_AUTHENTICATION_FAILED"); }
}
