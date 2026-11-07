package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.MerchantWebhookProperties;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.MerchantWebhook;
import com.fincore.payments.merchantwebhook.MerchantWebhookCrypto;
import com.fincore.payments.merchantwebhook.MerchantWebhookHttpClient;
import com.fincore.payments.repository.MerchantWebhookRepository;
import com.fincore.payments.service.MerchantWebhookTransactions;
import com.fincore.payments.service.PaymentService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class MerchantWebhookDatabaseTests {
    static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111119"), B=UUID.fromString("22222222-2222-4222-8222-222222222219");
    static final String USER="merchant-webhook-a", PASSWORD="merchant-webhook-password", MASTER="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    static final String MASTER_ID="m19-test-master-v1";
    static final AtomicReference<String> expectedSecret=new AtomicReference<>(),expectedKeyId=new AtomicReference<>();
    static final AtomicReference<Integer> responseStatus=new AtomicReference<>(204);
    static final AtomicInteger received=new AtomicInteger();
    static final HttpServer receiver=receiver();
    static String url() { return "http://127.0.0.1:"+receiver.getAddress().getPort()+"/webhooks"; }
    static HttpServer receiver() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/webhooks",exchange -> { try(exchange) {
                byte[] body=exchange.getRequestBody().readAllBytes(); long timestamp=Long.parseLong(exchange.getRequestHeaders().getFirst(MerchantWebhookHttpClient.TIMESTAMP));
                String keyId=exchange.getRequestHeaders().getFirst(MerchantWebhookHttpClient.KEY_ID);
                String signature=exchange.getRequestHeaders().getFirst(MerchantWebhookHttpClient.SIGNATURE);
                String secret=expectedSecret.get();
                if (body.length>0 && secret!=null && keyId.equals(expectedKeyId.get())
                        && MerchantWebhookCrypto.signature(secret,keyId,timestamp,body).equals(signature)) received.incrementAndGet();
                int status=responseStatus.get(); exchange.sendResponseHeaders(status,-1);
            }});
            server.start(); return server;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("fincore.merchant-webhooks.enabled",() -> true);
        r.add("fincore.merchant-webhooks.dispatch-enabled",() -> false);
        r.add("fincore.merchant-webhooks.master-key",() -> MASTER);
        r.add("fincore.merchant-webhooks.master-key-id",() -> MASTER_ID);
        r.add("fincore.merchant-webhooks.allowed-destinations",() -> List.of(url()));
        r.add("fincore.merchant-webhooks.max-attempts",() -> 2);
        r.add("fincore.merchant-webhooks.lease-seconds",() -> 5);
        r.add("fincore.merchant-webhooks.initial-backoff-millis",() -> 1);
        r.add("fincore.merchant-webhooks.max-backoff-millis",() -> 1);
    }
    @AfterAll static void closeReceiver() { receiver.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired MerchantWebhookTransactions transactions;
    @Autowired MerchantWebhookHttpClient http;
    @Autowired MerchantWebhookProperties properties;

    @BeforeEach void setup() {
        received.set(0); responseStatus.set(204); expectedSecret.set(null); expectedKeyId.set(null);
        jdbc.execute("TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans,projection_routes,projection_route_audit,projection_rebuild_items,projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,consumer_event_failures,consumer_event_positions,outbox_requeue_audit,payment_event_projections,consumed_payment_events,local_payment_event_inbox,payment_outbox,ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments");
        String hash=new BCryptPasswordEncoder(4).encode(PASSWORD);
        for (var entry:List.of(Map.entry(A,USER),Map.entry(B,"merchant-webhook-b")))
            jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES (?,?,?,true) ON CONFLICT(id) DO UPDATE SET username=EXCLUDED.username,password_hash=EXCLUDED.password_hash,enabled=true",entry.getKey(),entry.getValue(),hash);
    }

    MerchantWebhook.Created createSubscription() throws Exception {
        var result=mvc.perform(post("/api/v1/merchant/webhooks/subscriptions").with(httpBasic(USER,PASSWORD)).with(csrf())
                .contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(new MerchantWebhook.Create(UUID.randomUUID(),url()))))
                .andExpect(status().isOk()).andReturn();
        var created=mapper.readValue(result.getResponse().getContentAsByteArray(),MerchantWebhook.Created.class);
        expectedSecret.set(created.signingSecret()); expectedKeyId.set(created.subscription().subscriptionId().toString());
        return created;
    }

    @Test void paymentEventIsEnrolledSignedDeliveredAndVisibleOnlyToItsMerchant() throws Exception {
        var subscription=createSubscription();
        var payment=payments.createPayment(A,"m19-payment-"+UUID.randomUUID(),new CreatePaymentRequest("M19-ORDER",new java.math.BigDecimal("12.00"),"INR")).payment();
        payments.authorizePayment(payment.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_deliveries WHERE merchant_id=? AND payment_id=?",Long.class,A,payment.id())).isEqualTo(2);
        var first=transactions.claimNext().orElseThrow();
        var outcome=http.send(first.delivery().destinationUrl(),properties.allowedDestinations(),first.secret(),
                subscription.subscription().subscriptionId().toString(),first.lease().signedAt(),first.delivery().body(),properties.timeoutMillis());
        assertThat(outcome.acknowledged()).isTrue(); assertThat(transactions.finish(first,outcome)).isTrue();
        var mine=mvc.perform(get("/api/v1/merchant/webhooks/deliveries").param("paymentId",payment.id().toString())
                .with(httpBasic(USER,PASSWORD))).andExpect(status().isOk()).andReturn();
        assertThat(mine.getResponse().getContentAsString()).contains("DELIVERED","PENDING");
        mvc.perform(get("/api/v1/merchant/webhooks/deliveries").param("paymentId",payment.id().toString())
                .with(httpBasic("merchant-webhook-b",PASSWORD))).andExpect(status().isNotFound());
        assertThat(received).hasValue(1);
    }

    @Test void failedAttemptIsAuditedDeadLetterCanBeRetriedAndThenDelivered() throws Exception {
        var subscription=createSubscription();
        var payment=payments.createPayment(A,"m19-retry-"+UUID.randomUUID(),new CreatePaymentRequest("M19-RETRY",new java.math.BigDecimal("2.00"),"INR")).payment();
        var first=transactions.claimNext().orElseThrow(); responseStatus.set(503);
        var rejected=http.send(first.delivery().destinationUrl(),properties.allowedDestinations(),first.secret(),
                subscription.subscription().subscriptionId().toString(),first.lease().signedAt(),first.delivery().body(),properties.timeoutMillis());
        assertThat(rejected.acknowledged()).isFalse(); assertThat(transactions.finish(first,rejected)).isTrue();
        var secondFailure=transactions.claimNext().orElseThrow();
        var rejectedAgain=http.send(secondFailure.delivery().destinationUrl(),properties.allowedDestinations(),secondFailure.secret(),
                subscription.subscription().subscriptionId().toString(),secondFailure.lease().signedAt(),secondFailure.delivery().body(),properties.timeoutMillis());
        assertThat(rejectedAgain.acknowledged()).isFalse(); assertThat(transactions.finish(secondFailure,rejectedAgain)).isTrue();
        var current=jdbc.queryForMap("SELECT delivery_id,state,retry_generation FROM merchant_webhook_deliveries WHERE payment_id=?",payment.id());
        UUID delivery=(UUID)current.get("delivery_id"); assertThat(current.get("state")).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_attempts WHERE delivery_id=? AND state='FAILED'",Long.class,delivery)).isEqualTo(2);
        var retry=new MerchantWebhook.Retry(UUID.randomUUID(),0,"receiver restored");
        mvc.perform(post("/api/v1/merchant/webhooks/deliveries/{id}/retry",delivery).with(httpBasic(USER,PASSWORD)).with(csrf())
                .contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(retry))).andExpect(status().isOk());
        responseStatus.set(204); var second=transactions.claimNext().orElseThrow();
        var accepted=http.send(second.delivery().destinationUrl(),properties.allowedDestinations(),second.secret(),
                subscription.subscription().subscriptionId().toString(),second.lease().signedAt(),second.delivery().body(),properties.timeoutMillis());
        assertThat(accepted.acknowledged()).isTrue(); assertThat(transactions.finish(second,accepted)).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM merchant_webhook_deliveries WHERE delivery_id=?",String.class,delivery)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_attempts WHERE delivery_id=?",Long.class,delivery)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_retries WHERE delivery_id=?",Long.class,delivery)).isEqualTo(1);
    }

    @Test void expiredLeaseClosesOldAttemptAndFencesLateWorkerCompletion() throws Exception {
        createSubscription();
        var payment=payments.createPayment(A,"m19-lease-"+UUID.randomUUID(),new CreatePaymentRequest("M19-LEASE",new java.math.BigDecimal("3.00"),"INR")).payment();
        var stale=transactions.claimNext().orElseThrow();
        Thread.sleep(5200);
        var current=transactions.claimNext().orElseThrow();
        assertThat(current.delivery().view().deliveryId()).isEqualTo(stale.delivery().view().deliveryId());
        var late=new MerchantWebhookHttpClient.Outcome(true,204,null);
        assertThat(transactions.finish(stale,late)).isFalse();
        assertThat(transactions.finish(current,late)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_attempts WHERE delivery_id=? AND state='ABANDONED'",Long.class,
                stale.delivery().view().deliveryId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_attempts WHERE delivery_id=? AND state='DELIVERED'",Long.class,
                stale.delivery().view().deliveryId())).isEqualTo(1);
    }

    @Test void exactCreateRetryReturnsOriginalSecretButChangedIntentConflicts() throws Exception {
        UUID request=UUID.randomUUID(); var command=new MerchantWebhook.Create(request,url());
        var first=mvc.perform(post("/api/v1/merchant/webhooks/subscriptions").with(httpBasic(USER,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(command))).andExpect(status().isOk()).andReturn();
        var created=mapper.readValue(first.getResponse().getContentAsByteArray(),MerchantWebhook.Created.class);
        var replay=mvc.perform(post("/api/v1/merchant/webhooks/subscriptions").with(httpBasic(USER,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(command))).andExpect(status().isOk()).andReturn();
        var repeated=mapper.readValue(replay.getResponse().getContentAsByteArray(),MerchantWebhook.Created.class);
        assertThat(repeated.replayed()).isTrue(); assertThat(repeated.signingSecret()).isEqualTo(created.signingSecret());
        var changed=new MerchantWebhook.Create(request,"http://127.0.0.1:"+receiver.getAddress().getPort()+"/other");
        mvc.perform(post("/api/v1/merchant/webhooks/subscriptions").with(httpBasic(USER,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(changed))).andExpect(status().isConflict());
    }
}
