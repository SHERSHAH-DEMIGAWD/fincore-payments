package com.fincore.payments.provider;

import com.fincore.payments.config.ProviderProperties;
import com.fincore.payments.dto.ProviderOperation.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HttpSandboxPaymentProviderTest {
    Request request() { return new Request(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            Action.AUTHORIZE,Scenario.APPROVE,BigDecimal.TEN,"INR",""); }
    HttpSandboxPaymentProvider adapter(int port,int timeout) {
        return new HttpSandboxPaymentProvider(new ProviderProperties(true,URI.create("http://127.0.0.1:"+port),
                "test-only-provider-secret-0123456789",UUID.randomUUID(),timeout,30));
    }
    @Test void responseBudgetRefusesOversizedDocumentsWithoutApplyingThem() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/operations",e -> { try (e) { e.getRequestBody().readAllBytes(); byte[] body=" ".repeat(ProviderWire.MAX_BYTES+1).getBytes(); e.sendResponseHeaders(200,body.length); e.getResponseBody().write(body); } });
        server.start();
        try (var client=adapter(server.getAddress().getPort(),2000)) {
            assertThatThrownBy(() -> client.execute(request())).isInstanceOf(ProviderUncertainException.class);
        } finally { server.stop(0); }
    }
    @Test void redirectsAreNeverFollowedToAnotherEndpoint() throws Exception {
        var calls=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/operations",e -> { try (e) { e.getRequestBody().readAllBytes(); e.getResponseHeaders().set("Location","/redirect-target"); e.sendResponseHeaders(302,-1); } });
        server.createContext("/redirect-target",e -> { calls.incrementAndGet(); e.close(); }); server.start();
        try (var client=adapter(server.getAddress().getPort(),2000)) {
            assertThatThrownBy(() -> client.execute(request())).isInstanceOf(ProviderUncertainException.class); assertThat(calls).hasValue(0);
        } finally { server.stop(0); }
    }
    @Test void totalDeadlineIncludesAResponseBodyThatNeverFinishes() throws Exception {
        var release=new CountDownLatch(1); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/operations",e -> {
            try (e) { e.getRequestBody().readAllBytes(); e.sendResponseHeaders(200,100); e.getResponseBody().write('{'); e.getResponseBody().flush();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
        }); server.start();
        try (var client=adapter(server.getAddress().getPort(),200)) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> client.execute(request())).isInstanceOf(ProviderUncertainException.class));
        } finally { release.countDown(); server.stop(0); }
    }
}
