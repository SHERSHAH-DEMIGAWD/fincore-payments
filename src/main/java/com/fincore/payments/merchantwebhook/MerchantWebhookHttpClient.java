package com.fincore.payments.merchantwebhook;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete-body bounded delivery. An unconfirmed response is safe to retry with the same body/identity. */
@Component
public class MerchantWebhookHttpClient implements AutoCloseable {
    public static final String KEY_ID="Fincore-Webhook-Key-Id",TIMESTAMP="Fincore-Webhook-Timestamp",SIGNATURE="Fincore-Webhook-Signature";
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure) { }
            }).followRedirects(HttpClient.Redirect.NEVER).build();

    public record Outcome(boolean acknowledged,Integer httpStatus,String errorCode) { }

    public Outcome send(String destination,List<String> allowedDestinations,String secret,String signingKeyId,long signedAt,byte[] body,int timeoutMillis) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Merchant webhook HTTP must not join a database transaction.");
        if (!MerchantWebhookDestinationPolicy.allowed(destination,allowedDestinations)) return new Outcome(false,null,"DESTINATION_NOT_ALLOWED");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        var receiver=new LimitedBody(); var status=new AtomicInteger();
        try {
            if (body==null || body.length<1 || body.length>16384 || timeoutMillis<100 || timeoutMillis>10000)
                throw new IllegalArgumentException();
            byte[] exact=body.clone();
            String signature=MerchantWebhookCrypto.signature(secret,signingKeyId,signedAt,exact);
            var request=HttpRequest.newBuilder(URI.create(destination)).timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type","application/json").header(KEY_ID,signingKeyId)
                    .header(TIMESTAMP,Long.toString(signedAt)).header(SIGNATURE,signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(exact)).build();
            pending=client.sendAsync(request,info -> { status.set(info.statusCode()); return receiver; });
            var response=pending.get(timeoutMillis,TimeUnit.MILLISECONDS);
            boolean success=response.statusCode()>=200 && response.statusCode()<300;
            return new Outcome(success,response.statusCode(),success ? null : "HTTP_REJECTED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return uncertain(status);
        } catch (Exception unconfirmed) { return uncertain(status); }
        finally {
            if (pending!=null && !pending.isDone()) pending.cancel(true);
            receiver.cancel();
        }
    }

    private static Outcome uncertain(AtomicInteger status) { return new Outcome(false,status.get()==0 ? null : status.get(),"NETWORK_UNCONFIRMED"); }
    @jakarta.annotation.PreDestroy @Override public void close() { client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription=subscription;
            if (cancelled) subscription.cancel(); else subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer:buffers) {
                if (buffer.remaining()>8192-bytes.size()) {
                    cancel(); result.completeExceptionally(new IllegalArgumentException("Merchant webhook response exceeds budget.")); return;
                }
                byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
        void cancel() { cancelled=true; if (subscription!=null) subscription.cancel(); }
    }
}
