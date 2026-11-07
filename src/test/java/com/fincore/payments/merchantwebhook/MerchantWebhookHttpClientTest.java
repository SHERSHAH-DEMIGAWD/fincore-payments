package com.fincore.payments.merchantwebhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class MerchantWebhookHttpClientTest {
    static final String SECRET="http-test-only-merchant-webhook-secret-12345",KEY_ID="11111111-1111-4111-8111-111111111119";
    static final byte[] BODY="{\"deliveryId\":\"stable-delivery\"}".getBytes(StandardCharsets.UTF_8);
    String destination(HttpServer server) { return "http://127.0.0.1:"+server.getAddress().getPort()+"/webhooks"; }
    MerchantWebhookHttpClient.Outcome send(MerchantWebhookHttpClient client,String url,long time,int timeout) {
        return client.send(url,List.of(url),SECRET,KEY_ID,time,BODY,timeout);
    }

    @ParameterizedTest @ValueSource(ints={200,202,204,299,301,400,429,500,503})
    void onlyComplete2xxResponsesAcknowledgeAndHeadersBindExactBody(int status) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var received=new AtomicReference<byte[]>();
        var headers=new AtomicReference<com.sun.net.httpserver.Headers>();
        server.createContext("/webhooks",exchange -> { try(exchange) {
            received.set(exchange.getRequestBody().readAllBytes()); headers.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(status,-1);
        }}); server.start(); String url=destination(server);
        try (var client=new MerchantWebhookHttpClient()) {
            var result=send(client,url,1893456000,2000);
            assertThat(result.acknowledged()).isEqualTo(status>=200 && status<300); assertThat(result.httpStatus()).isEqualTo(status);
            assertThat(result.errorCode()).isEqualTo(result.acknowledged() ? null : "HTTP_REJECTED");
            assertThat(received.get()).isEqualTo(BODY);
            assertThat(headers.get().getFirst(MerchantWebhookHttpClient.KEY_ID)).isEqualTo(KEY_ID);
            assertThat(headers.get().getFirst(MerchantWebhookHttpClient.TIMESTAMP)).isEqualTo("1893456000");
            assertThat(headers.get().getFirst(MerchantWebhookHttpClient.SIGNATURE)).isEqualTo(MerchantWebhookCrypto.signature(SECRET,KEY_ID,1893456000,BODY));
            assertThat(headers.get().getFirst("Authorization")).isNull();
        } finally { server.stop(0); }
    }

    @Test void aLostAcknowledgementCanRepeatTheSameBodyWithFreshSignedHeaders() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var calls=new AtomicInteger();
        var bodies=Collections.synchronizedList(new ArrayList<byte[]>()); var signatures=Collections.synchronizedList(new ArrayList<String>());
        server.createContext("/webhooks",exchange -> { try(exchange) {
            bodies.add(exchange.getRequestBody().readAllBytes()); signatures.add(exchange.getRequestHeaders().getFirst(MerchantWebhookHttpClient.SIGNATURE));
            if (calls.incrementAndGet()==1) { exchange.sendResponseHeaders(200,100); exchange.getResponseBody().write('{'); }
            else exchange.sendResponseHeaders(204,-1);
        }}); server.start(); String url=destination(server);
        try (var client=new MerchantWebhookHttpClient()) {
            var first=send(client,url,1893456000,2000); var second=send(client,url,1893456001,2000);
            assertThat(first.acknowledged()).isFalse(); assertThat(first.errorCode()).isEqualTo("NETWORK_UNCONFIRMED");
            assertThat(second.acknowledged()).isTrue(); assertThat(calls).hasValue(2);
            assertThat(bodies.getFirst()).isEqualTo(bodies.getLast()); assertThat(signatures.getFirst()).isNotEqualTo(signatures.getLast());
        } finally { server.stop(0); }
    }

    @Test void redirectTargetsAreNeverRequested() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var redirected=new AtomicInteger();
        server.createContext("/webhooks",exchange -> { try(exchange) { exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Location","/other"); exchange.sendResponseHeaders(302,-1); } });
        server.createContext("/other",exchange -> { redirected.incrementAndGet(); exchange.close(); }); server.start();
        try (var client=new MerchantWebhookHttpClient()) { assertThat(send(client,destination(server),1893456000,2000).acknowledged()).isFalse(); assertThat(redirected).hasValue(0); }
        finally { server.stop(0); }
    }

    @Test void globalProxySelectionIsNotConsulted() throws Exception {
        ProxySelector original=ProxySelector.getDefault(); var proxyCalls=new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { proxyCalls.incrementAndGet(); return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1))); }
            @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure) { }
        });
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/webhooks",exchange -> { try(exchange) { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(204,-1); } }); server.start();
        try (var client=new MerchantWebhookHttpClient()) { assertThat(send(client,destination(server),1893456000,2000).acknowledged()).isTrue(); assertThat(proxyCalls).hasValue(0); }
        finally { server.stop(0); ProxySelector.setDefault(original); }
    }

    @Test void responseBudgetRejectsOversizedAcknowledgements() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/webhooks",exchange -> { try(exchange) {
            exchange.getRequestBody().readAllBytes(); byte[] body=new byte[8193]; exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
        }}); server.start();
        try (var client=new MerchantWebhookHttpClient()) { var result=send(client,destination(server),1893456000,2000); assertThat(result.acknowledged()).isFalse(); assertThat(result.errorCode()).isEqualTo("NETWORK_UNCONFIRMED"); }
        finally { server.stop(0); }
    }

    @Test void completeBodyDeadlineRefusesAStalledResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var release=new CountDownLatch(1);
        server.createContext("/webhooks",exchange -> { try(exchange) {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200,100); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }}); server.start();
        try (var client=new MerchantWebhookHttpClient()) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),() -> {
                var result=send(client,destination(server),1893456000,100); assertThat(result.acknowledged()).isFalse(); assertThat(result.errorCode()).isEqualTo("NETWORK_UNCONFIRMED");
            });
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void unapprovedDestinationAndActiveTransactionsNeverSend() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var calls=new AtomicInteger();
        server.createContext("/webhooks",exchange -> { calls.incrementAndGet(); exchange.close(); }); server.start();
        try (var client=new MerchantWebhookHttpClient()) {
            String url=destination(server);
            var result=client.send(url,List.of(),SECRET,KEY_ID,1893456000,BODY,2000);
            assertThat(result.errorCode()).isEqualTo("DESTINATION_NOT_ALLOWED");
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> send(client,url,1893456000,2000)).isInstanceOf(IllegalStateException.class).hasMessageContaining("must not join"); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThat(calls).hasValue(0);
        } finally { server.stop(0); }
    }
}
