package com.fincore.payments.deployment;

import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Bounds the whole response, including a body that stalls after headers arrive. */
final class SandboxHttp {
    private SandboxHttp() { }
    static HttpResponse<byte[]> send(HttpClient client,HttpRequest request,int maximum,Duration timeout)throws Exception {
        if(maximum<1 || maximum>2*1024*1024 || timeout.isNegative() || timeout.isZero())throw new IllegalArgumentException();
        var pending=client.sendAsync(request,info->new LimitedBody(maximum));
        try{return pending.get(timeout.toNanos(),TimeUnit.NANOSECONDS);}
        catch(ExecutionException error){throw new IOException("Sandbox HTTP exchange failed.");}
        catch(TimeoutException error){throw new IOException("Sandbox HTTP exchange timed out.");}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int maximum){this.maximum=maximum;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks){if((long)bytes.size()+chunk.remaining()>maximum){subscription.cancel();result.completeExceptionally(new IOException("Sandbox body exceeds limit."));return;}
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
