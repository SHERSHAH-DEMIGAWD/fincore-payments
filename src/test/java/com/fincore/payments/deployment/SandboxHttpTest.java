package com.fincore.payments.deployment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class SandboxHttpTest {
    @Test void collectsHeadersAndBytesButCancelsOversizedBodies()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.createContext("/",exchange->{try(exchange){byte[] body="bounded-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("X-Proof","present");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}});server.start();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).GET().build();
            var response=SandboxHttp.send(client,request,32,Duration.ofSeconds(2));assertThat(response.headers().firstValue("X-Proof")).contains("present");assertThat(new String(response.body())).isEqualTo("bounded-body");
            assertThatThrownBy(()->SandboxHttp.send(client,request,4,Duration.ofSeconds(2))).isInstanceOf(IOException.class);
        }finally{server.stop(0);}
    }
    @Test void timeoutStillAppliesAfterResponseHeadersArrive()throws Exception {
        var headers=new CountDownLatch(1);var release=new CountDownLatch(1);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.createContext("/",exchange->{try(exchange){exchange.sendResponseHeaders(200,2);headers.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}exchange.getResponseBody().write(new byte[]{1,2});}});server.start();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).GET().build();long started=System.nanoTime();
            assertThatThrownBy(()->SandboxHttp.send(client,request,32,Duration.ofMillis(400))).isInstanceOf(IOException.class);
            assertThat(headers.await(1,TimeUnit.SECONDS)).isTrue();assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));release.countDown();
        }finally{release.countDown();server.stop(0);}
    }
}
