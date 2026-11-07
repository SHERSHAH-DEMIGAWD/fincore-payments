package com.fincore.payments.provider;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Shared sandbox transport: no proxy/redirect, full-body deadline, bounded subscription. */
final class BoundedProviderHttp {
    private BoundedProviderHttp() { }
    static HttpClient client(int timeoutMillis) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis))
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure) { }
                }).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    static HttpResponse<byte[]> send(HttpClient client,HttpRequest request,int timeoutMillis) throws Exception {
        var body=new LimitedBody(); CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request,info -> body);
            return pending.get(timeoutMillis,TimeUnit.MILLISECONDS);
        } finally {
            if (pending!=null && !pending.isDone()) pending.cancel(true);
            body.cancel();
        }
    }
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
                if (buffer.remaining()>ProviderWire.MAX_BYTES-bytes.size()) {
                    cancel(); result.completeExceptionally(new IllegalArgumentException("Sandbox response too large.")); return;
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
