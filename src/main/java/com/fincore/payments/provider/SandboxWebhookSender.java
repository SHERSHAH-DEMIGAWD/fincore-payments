package com.fincore.payments.provider;

import com.fincore.payments.dto.ProviderOperation.Response;
import com.fincore.payments.dto.ProviderWebhook.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional sandbox sender. Decisions remain durable even when delivery is unconfirmed. */
public final class SandboxWebhookSender implements AutoCloseable {
    private final URI url;
    private final String key,keyId;
    private final HttpClient client;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64));
    public SandboxWebhookSender(URI url,String key,String keyId) {
        if (!ProviderWebhookWire.receiverUri(url) || key==null || !key.matches("[!-~]{32,256}")
                || keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Sandbox webhook configuration is invalid.");
        this.url=url; this.key=key; this.keyId=keyId; this.client=BoundedProviderHttp.client(2000);
    }
    public static SandboxWebhookSender fromEnvironment(Map<String,String> env) {
        String url=env.getOrDefault("FINCORE_SANDBOX_WEBHOOK_URL",""),key=env.getOrDefault("FINCORE_SANDBOX_WEBHOOK_KEY","");
        String keyId=env.getOrDefault("FINCORE_SANDBOX_WEBHOOK_KEY_ID","");
        if (url.isEmpty() && key.isEmpty() && keyId.isEmpty()) return null;
        return new SandboxWebhookSender(URI.create(url),key,keyId.isEmpty() ? "sandbox-v1" : keyId);
    }
    public void enqueue(Response response) {
        try { executor.execute(() -> { if (!deliver(response).acknowledged()) unconfirmed(response); }); }
        catch (RejectedExecutionException fullOrClosed) { unconfirmed(response); }
    }
    private static void unconfirmed(Response response) {
        System.err.println("{\"status\":\"SANDBOX_WEBHOOK_UNCONFIRMED\",\"eventId\":\""+ProviderWebhookWire.event(response).eventId()+"\"}");
    }
    public Delivery deliver(Response response) {
        var event=ProviderWebhookWire.event(response);
        try {
            byte[] body=ProviderWire.mapper().writeValueAsBytes(event);
            String timestamp=Long.toString(Instant.now().getEpochSecond());
            var request=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json")
                    .header(ProviderWebhookWire.KEY_ID,keyId).header(ProviderWebhookWire.TIMESTAMP,timestamp)
                    .header(ProviderWebhookWire.SIGNATURE,ProviderWebhookWire.signature(key,keyId,timestamp,body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var result=BoundedProviderHttp.send(client,request,2000);
            if (result.statusCode()!=200) return new Delivery(event.eventId(),false,null);
            var ack=ProviderWire.read(result.body(),Acknowledgement.class);
            if (ack==null || ack.receiptId()==null || ack.receiptId().equals(new UUID(0,0)) || !event.eventId().equals(ack.eventId()) || ack.disposition()==null)
                return new Delivery(event.eventId(),false,null);
            return new Delivery(event.eventId(),true,ack.disposition());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return new Delivery(event.eventId(),false,null);
        } catch (Exception uncertain) { return new Delivery(event.eventId(),false,null); }
    }
    @Override public void close() { executor.shutdownNow(); client.shutdownNow(); }
}
