package com.fincore.payments.provider;

import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.dto.ProviderWebhook.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SandboxWebhookSenderTest {
    static final String KEY="sender-unit-test-only-webhook-key-12345",KEY_ID="sandbox-v1";
    Response response() {
        UUID instance=UUID.randomUUID(),operation=UUID.randomUUID(),payment=UUID.randomUUID();
        return new Response(1,instance,operation,payment,UUID.randomUUID(),Action.CAPTURE,new BigDecimal("10.00"),"INR",Outcome.APPROVED,"sandbox-pay-"+payment,"sandbox-op-"+operation);
    }
    SandboxWebhookSender sender(int port) { return new SandboxWebhookSender(URI.create("http://127.0.0.1:"+port+ProviderWebhookWire.PATH),KEY,KEY_ID); }
    @ParameterizedTest @ValueSource(strings={"https://example.com/api/v1/provider-webhooks/sandbox","http://127.0.0.1:8080/other","http://user@127.0.0.1/api/v1/provider-webhooks/sandbox","http://127.0.0.1/api/v1/provider-webhooks/sandbox?x=1"})
    void refusesNonlocalOrAmbiguousDestinations(String url) {
        assertThatThrownBy(() -> new SandboxWebhookSender(URI.create(url),KEY,KEY_ID)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void optionalEnvironmentIsDisabledByDefaultAndPartialSettingsRefuseStartup() {
        assertThat(SandboxWebhookSender.fromEnvironment(Map.of())).isNull();
        assertThatThrownBy(() -> SandboxWebhookSender.fromEnvironment(Map.of("FINCORE_SANDBOX_WEBHOOK_KEY",KEY))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SandboxWebhookSender.fromEnvironment(Map.of("FINCORE_SANDBOX_WEBHOOK_URL","http://127.0.0.1:8080"+ProviderWebhookWire.PATH))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unconfirmedDeliveryCanBeResentWithStableBodyAndVerifiedSignature() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var calls=new AtomicInteger();
        var bodies=Collections.synchronizedList(new ArrayList<byte[]>());
        server.createContext(ProviderWebhookWire.PATH,e -> {
            try (e) {
                byte[] body=e.getRequestBody().readNBytes(ProviderWire.MAX_BYTES+1); bodies.add(body);
                String time=e.getRequestHeaders().getFirst(ProviderWebhookWire.TIMESTAMP);
                assertThat(e.getRequestHeaders().getFirst(ProviderWebhookWire.SIGNATURE)).isEqualTo(ProviderWebhookWire.signature(KEY,KEY_ID,time,body));
                var event=ProviderWebhookWire.read(body);
                byte[] ack=ProviderWire.mapper().writeValueAsBytes(new Acknowledgement(UUID.randomUUID(),event.eventId(),Disposition.ALREADY_RECORDED,false));
                e.sendResponseHeaders(calls.incrementAndGet()==1 ? 503 : 200,ack.length); e.getResponseBody().write(ack);
            }
        }); server.start();
        try (var sender=sender(server.getAddress().getPort())) {
            var result=response(); assertThat(sender.deliver(result).acknowledged()).isFalse();
            var delivery=sender.deliver(result); assertThat(delivery.acknowledged()).isTrue(); assertThat(delivery.disposition()).isEqualTo(Disposition.ALREADY_RECORDED);
            assertThat(bodies).hasSize(2); assertThat(bodies.getFirst()).isEqualTo(bodies.getLast());
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings={"oversized","redirect","wrong-event","invalid-json"})
    void unusableAcknowledgementsRemainUnconfirmed(String failure) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var redirected=new AtomicInteger();
        server.createContext(ProviderWebhookWire.PATH,e -> { try (e) {
            e.getRequestBody().readAllBytes(); byte[] body=failure.equals("oversized") ? new byte[ProviderWire.MAX_BYTES+1]
                    : failure.equals("invalid-json") ? "{}".getBytes() : ProviderWire.mapper().writeValueAsBytes(new Acknowledgement(UUID.randomUUID(),UUID.randomUUID(),Disposition.CONFIRMED,false));
            if (failure.equals("redirect")) e.getResponseHeaders().set("Location","/redirected");
            e.sendResponseHeaders(failure.equals("redirect") ? 302 : 200,body.length); e.getResponseBody().write(body);
        }});
        server.createContext("/redirected",e -> { redirected.incrementAndGet(); e.close(); }); server.start();
        try (var sender=sender(server.getAddress().getPort())) { assertThat(sender.deliver(response()).acknowledged()).isFalse(); assertThat(redirected).hasValue(0); }
        finally { server.stop(0); }
    }
}
