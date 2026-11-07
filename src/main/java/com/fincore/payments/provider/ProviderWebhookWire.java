package com.fincore.payments.provider;

import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.dto.ProviderWebhook.Event;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class ProviderWebhookWire {
    public static final String PATH="/api/v1/provider-webhooks/sandbox";
    public static final String TIMESTAMP="Fincore-Webhook-Timestamp",KEY_ID="Fincore-Webhook-Key-Id",SIGNATURE="Fincore-Webhook-Signature";
    private ProviderWebhookWire() { }
    public static Event event(Response response) {
        UUID id=UUID.nameUUIDFromBytes(("fincore-sandbox-result-v1:"+response.instanceId()+":"+response.operationId()).getBytes(StandardCharsets.UTF_8));
        return new Event(1,id,response);
    }
    public static Event read(byte[] body) throws java.io.IOException {
        var e=ProviderWire.read(body,Event.class);
        if (e==null || e.version()!=1 || !identity(e.eventId()) || e.result()==null) throw new IllegalArgumentException();
        var r=e.result();
        if (r.version()!=1 || !identity(r.instanceId()) || !identity(r.operationId()) || !identity(r.paymentId())
                || !identity(r.merchantId()) || r.action()==null || r.outcome()==null || !ProviderWire.validAmount(r.amount())
                || r.currency()==null || !r.currency().matches("[A-Z]{3}")
                || !Objects.equals(r.providerOperationId(),"sandbox-op-"+r.operationId())
                || !Objects.equals(r.providerPaymentId(),r.outcome()==Outcome.DECLINED && r.action()==Action.AUTHORIZE ? "" : "sandbox-pay-"+r.paymentId()))
            throw new IllegalArgumentException();
        return e;
    }
    private static boolean identity(UUID id) { return id!=null && !id.equals(new UUID(0,0)); }
    public static String signature(String key,String keyId,String timestamp,byte[] body) {
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
            mac.update(("fincore-provider-webhook-v1\n"+keyId+"\n"+timestamp+"\n").getBytes(StandardCharsets.US_ASCII));
            return "v1="+HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException("Webhook signature algorithm is unavailable."); }
    }
    public static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (GeneralSecurityException unavailable) { throw new IllegalStateException("Webhook digest algorithm is unavailable."); }
    }
    public static boolean receiverUri(URI uri) {
        return uri!=null && uri.getScheme()!=null && Set.of("http","https").contains(uri.getScheme()) && uri.getHost()!=null
                && Set.of("127.0.0.1","localhost","::1","[::1]").contains(uri.getHost())
                && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                && PATH.equals(uri.getPath()) && uri.getPort()!=0 && uri.getPort()<=65535;
    }
}
