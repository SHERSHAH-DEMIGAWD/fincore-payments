package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.*;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.dto.ProviderWebhook.*;
import com.fincore.payments.provider.*;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ProviderWebhookDatabaseTests {
    static final UUID INSTANCE=UUID.randomUUID(),A=UUID.fromString("11111111-1111-4111-8111-111111111118"),B=UUID.fromString("22222222-2222-4222-8222-222222222218");
    static final String PASSWORD="webhook-merchant-test-password",API_KEY="test-only-provider-api-key-12345678",KEY="test-only-separate-webhook-key-12345678",KEY_ID="sandbox-v1";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    static SandboxProviderServer sandbox;
    static synchronized SandboxProviderServer sandbox() {
        if (sandbox==null) try { sandbox=new SandboxProviderServer(0,Files.createTempDirectory("fincore-webhook-provider-"),INSTANCE,API_KEY); }
        catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
        return sandbox;
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        int port=sandbox().port();
        r.add("fincore.provider.enabled",() -> true); r.add("fincore.provider.base-url",() -> "http://127.0.0.1:"+port);
        r.add("fincore.provider.api-key",() -> API_KEY); r.add("fincore.provider.instance-id",() -> INSTANCE.toString());
        r.add("fincore.provider-webhook.enabled",() -> true); r.add("fincore.provider-webhook.signing-key",() -> KEY);
        r.add("fincore.provider-webhook.key-id",() -> KEY_ID); r.add("fincore.provider-webhook.instance-id",() -> INSTANCE.toString());
    }
    @AfterAll static void closeProvider() throws Exception { if (sandbox!=null) { sandbox.close(); sandbox=null; } }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired ProviderOperationTransactions transactions;
    @MockitoSpyBean ProviderOperationRepository operations;
    @MockitoSpyBean ProviderProperties providerProperties;
    @MockitoSpyBean ProviderWebhookProperties webhookProperties;
    @Autowired ProviderWebhookService webhooks;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean ProviderWebhookRepository receipts;
    @MockitoSpyBean HttpSandboxPaymentProvider adapter;
    @MockitoSpyBean OutboxService outbox;
    @LocalServerPort int apiPort;
    UUID payment;
    HttpSandboxPaymentProvider adapterTarget;
    OutboxService outboxTarget;

    @BeforeEach void setup() {
        adapterTarget=AopTestUtils.getUltimateTargetObject(adapter); outboxTarget=AopTestUtils.getUltimateTargetObject(outbox);
        jdbc.execute("""
            TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,
                projection_preservation_approvals,projection_preservations,projection_purge_plans,projection_routes,projection_route_audit,
                projection_rebuild_items,projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,
                consumer_event_failures,consumer_event_positions,outbox_requeue_audit,payment_event_projections,consumed_payment_events,
                local_payment_event_inbox,payment_outbox,ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments
            """);
        for (var entry:List.of(Map.entry(A,"webhook-merchant-a"),Map.entry(B,"webhook-merchant-b")))
            jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES (?,?,?,true) ON CONFLICT (id) DO UPDATE SET password_hash=EXCLUDED.password_hash,enabled=true",entry.getKey(),entry.getValue(),HASH);
        payment=payments.createPayment(A,"webhook-source-"+UUID.randomUUID(),new CreatePaymentRequest("WEBHOOK-PRIVATE-ORDER",new BigDecimal("10.00"),"INR")).payment().id();
    }
    record Pending(UUID operation,UUID token,Response response) { }
    Pending pending(Action action,Scenario scenario,boolean uncertain) throws Exception {
        var prepared=transactions.prepare(A,payment,new Command(UUID.randomUUID(),action,scenario));
        UUID id=prepared.operation().view().operationId(); var claim=transactions.claim(A,id);
        var response=adapter.execute(claim.operation().request());
        if (uncertain) transactions.uncertain(A,id,claim.token());
        return new Pending(id,claim.token(),response);
    }
    Pending pending(Action action) throws Exception { return pending(action,Scenario.APPROVE,true); }
    Event event(Response response) { return ProviderWebhookWire.event(response); }
    byte[] bytes(Event event) throws Exception { return ProviderWire.mapper().writeValueAsBytes(event); }
    org.springframework.test.web.servlet.ResultActions callback(byte[] body) throws Exception {
        String time=Long.toString(Instant.now().getEpochSecond());
        return mvc.perform(post(ProviderWebhookWire.PATH).contentType(APPLICATION_JSON).content(body)
                .header(ProviderWebhookWire.KEY_ID,KEY_ID).header(ProviderWebhookWire.TIMESTAMP,time)
                .header(ProviderWebhookWire.SIGNATURE,ProviderWebhookWire.signature(KEY,KEY_ID,time,body)));
    }
    Acknowledgement accept(Event event) throws Exception {
        var result=callback(bytes(event)).andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(),Acknowledgement.class);
    }
    void confirm(Action action) throws Exception { assertThat(accept(event(pending(action).response())).disposition()).isEqualTo(Disposition.CONFIRMED); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    void state(String expected,long version) {
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?",Long.class,payment)).isEqualTo(version);
    }
    String ownedPage(UUID operation) { return "/api/v1/payments/"+payment+"/provider-operations/"+operation+"/webhooks"; }

    @Test void verifiedCallbackRecoversUnknownAuthorizationWithoutAnotherOutboundAttempt() throws Exception {
        var p=pending(Action.AUTHORIZE); var receipt=accept(event(p.response()));
        assertThat(receipt.disposition()).isEqualTo(Disposition.CONFIRMED); assertThat(receipt.duplicate()).isFalse();
        state("AUTHORIZED",1); assertThat(count("provider_webhook_receipts")).isEqualTo(1); assertThat(count("payment_outbox")).isEqualTo(2);
        assertThat(operations.byId(A,p.operation(),false).orElseThrow().view().attempts()).isEqualTo(1);
    }
    @Test void callbackFullLifecyclePostsOneCaptureAndOneReversal() throws Exception {
        confirm(Action.AUTHORIZE); confirm(Action.CAPTURE); confirm(Action.REFUND);
        state("REFUNDED",3); assertThat(count("ledger_transactions")).isEqualTo(2); assertThat(count("ledger_entries")).isEqualTo(4);
        assertThat(count("payment_outbox")).isEqualTo(4); assertThat(count("provider_webhook_receipts")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(strings={"AUTHORIZE","CAPTURE","REFUND"})
    void confirmedDeclinesHaveOnlyTheirDocumentedLocalEffect(String name) throws Exception {
        Action action=Action.valueOf(name);
        if (action!=Action.AUTHORIZE) confirm(Action.AUTHORIZE);
        if (action==Action.REFUND) confirm(Action.CAPTURE);
        long journals=count("ledger_transactions"),events=count("payment_outbox");
        var p=pending(action,Scenario.DECLINE,true); assertThat(accept(event(p.response())).disposition()).isEqualTo(Disposition.CONFIRMED);
        state(action==Action.AUTHORIZE ? "FAILED" : action==Action.CAPTURE ? "AUTHORIZED" : "CAPTURED",action==Action.REFUND ? 2 : 1);
        assertThat(count("ledger_transactions")).isEqualTo(journals); assertThat(count("payment_outbox")).isEqualTo(events+(action==Action.AUTHORIZE ? 1 : 0));
    }
    @Test void exactDuplicateReusesReceiptWithoutRepeatingFinancialEffects() throws Exception {
        confirm(Action.AUTHORIZE); var p=pending(Action.CAPTURE); var event=event(p.response());
        var first=accept(event); var duplicate=accept(event);
        assertThat(duplicate.receiptId()).isEqualTo(first.receiptId()); assertThat(duplicate.duplicate()).isTrue();
        state("CAPTURED",2); assertThat(count("ledger_transactions")).isEqualTo(1); assertThat(count("provider_webhook_receipts")).isEqualTo(2);
    }
    @Test void differentEventForSameResultIsRecordedWithoutRepeatingApplication() throws Exception {
        var p=pending(Action.AUTHORIZE); accept(event(p.response()));
        assertThat(accept(new Event(1,UUID.randomUUID(),p.response())).disposition()).isEqualTo(Disposition.ALREADY_RECORDED);
        state("AUTHORIZED",1); assertThat(count("payment_outbox")).isEqualTo(2); assertThat(count("provider_webhook_receipts")).isEqualTo(2);
    }
    @Test void lateReverseOrderCallbacksCannotRewindARefundedPayment() throws Exception {
        List<Response> confirmations=new ArrayList<>();
        for (Action action:List.of(Action.AUTHORIZE,Action.CAPTURE,Action.REFUND)) {
            var p=pending(action,Scenario.APPROVE,false); transactions.finish(A,payment,p.operation(),p.token(),p.response()); confirmations.add(p.response());
        }
        for (var response:confirmations.reversed()) assertThat(accept(event(response)).disposition()).isEqualTo(Disposition.ALREADY_RECORDED);
        state("REFUNDED",3); assertThat(count("ledger_transactions")).isEqualTo(2); assertThat(count("payment_outbox")).isEqualTo(4);
    }
    @Test void reusedEventIdentityWithChangedExactBodyConflictsAndKeepsOriginalEvidence() throws Exception {
        var p=pending(Action.AUTHORIZE); var first=event(p.response()); accept(first);
        byte[] changed=(new String(bytes(first),java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        callback(changed).andExpect(status().isConflict()); state("AUTHORIZED",1); assertThat(count("provider_webhook_receipts")).isEqualTo(1);
    }
    @Test void contradictoryNewEventIsRetainedAsRejectedWithoutChangingKnownOutcome() throws Exception {
        var p=pending(Action.AUTHORIZE); accept(event(p.response())); var r=p.response();
        var contradiction=new Response(1,INSTANCE,r.operationId(),payment,A,r.action(),r.amount(),r.currency(),Outcome.DECLINED,"",r.providerOperationId());
        assertThat(accept(new Event(1,UUID.randomUUID(),contradiction)).disposition()).isEqualTo(Disposition.REJECTED);
        state("AUTHORIZED",1); assertThat(jdbc.queryForObject("SELECT rejection_code FROM provider_webhook_receipts WHERE outcome='DECLINED'",String.class)).isEqualTo("OUTCOME_CONFLICT");
    }
    @ParameterizedTest @ValueSource(strings={"amount","currency","action"})
    void signedButMismatchedIntentIsAuditedWithoutApplication(String field) throws Exception {
        var p=pending(Action.AUTHORIZE); var r=p.response();
        var mismatch=new Response(1,INSTANCE,r.operationId(),payment,A,field.equals("action") ? Action.CAPTURE : r.action(),
                field.equals("amount") ? new BigDecimal("20.00") : r.amount(),field.equals("currency") ? "USD" : r.currency(),r.outcome(),r.providerPaymentId(),r.providerOperationId());
        assertThat(accept(event(mismatch)).disposition()).isEqualTo(Disposition.REJECTED); state("CREATED",0);
        assertThat(count("ledger_transactions")).isZero(); assertThat(jdbc.queryForObject("SELECT rejection_code FROM provider_webhook_receipts",String.class)).isEqualTo("INTENT_MISMATCH");
    }
    @ParameterizedTest @ValueSource(strings={"unknown","other-merchant"})
    void unmatchedSignedEventsCannotCreateOrAdoptPayments(String kind) throws Exception {
        var p=pending(Action.AUTHORIZE); var r=p.response(); UUID id=kind.equals("unknown") ? UUID.randomUUID() : r.operationId();
        var unmatched=new Response(1,INSTANCE,id,payment,kind.equals("other-merchant") ? B : A,r.action(),r.amount(),r.currency(),r.outcome(),r.providerPaymentId(),"sandbox-op-"+id);
        assertThat(accept(event(unmatched)).disposition()).isEqualTo(Disposition.UNMATCHED); state("CREATED",0);
        assertThat(count("payments")).isEqualTo(1); assertThat(count("ledger_transactions")).isZero();
    }
    @Test void aPreparedButNeverDispatchedCommandCannotBeConfirmed() throws Exception {
        var prepared=transactions.prepare(A,payment,new Command(UUID.randomUUID(),Action.AUTHORIZE,Scenario.APPROVE)); var r=prepared.operation().request();
        var response=new Response(1,INSTANCE,r.operationId(),payment,A,r.action(),r.amount(),r.currency(),Outcome.APPROVED,"sandbox-pay-"+payment,"sandbox-op-"+r.operationId());
        assertThat(accept(event(response)).disposition()).isEqualTo(Disposition.REJECTED); state("CREATED",0);
        assertThat(jdbc.queryForObject("SELECT rejection_code FROM provider_webhook_receipts",String.class)).isEqualTo("NOT_DISPATCHED");
    }
    @Test void callbackDuringLiveAttemptFencesLateHttpCompletionAndUncertainty() throws Exception {
        confirm(Action.AUTHORIZE); var p=pending(Action.CAPTURE,Scenario.APPROVE,false);
        assertThat(accept(event(p.response())).disposition()).isEqualTo(Disposition.CONFIRMED);
        assertThat(transactions.finish(A,payment,p.operation(),p.token(),p.response()).state()).isEqualTo(State.APPLIED);
        assertThat(transactions.uncertain(A,p.operation(),p.token()).state()).isEqualTo(State.APPLIED);
        state("CAPTURED",2); assertThat(count("ledger_transactions")).isEqualTo(1);
    }
    @Test void concurrentExactCallbacksCommitOneReceiptAndOneApplication() throws Exception {
        var p=pending(Action.AUTHORIZE); var event=event(p.response());
        try (var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var one=executor.submit(() -> { gate.await(); return accept(event); });
            var two=executor.submit(() -> { gate.await(); return accept(event); }); gate.countDown();
            var a=one.get(10,TimeUnit.SECONDS); var b=two.get(10,TimeUnit.SECONDS);
            assertThat(a.receiptId()).isEqualTo(b.receiptId()); assertThat(a.duplicate()).isNotEqualTo(b.duplicate());
        }
        state("AUTHORIZED",1); assertThat(count("provider_webhook_receipts")).isEqualTo(1); assertThat(count("payment_outbox")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"outbox","receipt"})
    void lateFailureRollsBackReceiptAndAllFinancialCompletionThenRedeliveryRecovers(String failure) throws Exception {
        confirm(Action.AUTHORIZE); var p=pending(Action.CAPTURE); byte[] body=bytes(event(p.response())); long before=count("provider_webhook_receipts");
        if (failure.equals("outbox")) doThrow(new IllegalStateException("Injected late outbox failure")).when(outboxTarget).record(any());
        else doThrow(new IllegalStateException("Injected late receipt failure")).when(receipts).finish(any(),any());
        callback(body).andExpect(status().is(failure.equals("receipt") ? 503 : 500)); state("AUTHORIZED",1);
        assertThat(count("ledger_transactions")).isZero(); assertThat(count("provider_webhook_receipts")).isEqualTo(before);
        assertThat(operations.byId(A,p.operation(),false).orElseThrow().view().state()).isEqualTo(State.UNKNOWN);
        doCallRealMethod().when(outboxTarget).record(any()); doCallRealMethod().when(receipts).finish(any(),any());
        callback(body).andExpect(status().isOk()); state("CAPTURED",2); assertThat(count("ledger_transactions")).isEqualTo(1);
    }
    @Test void busySourceReturnsRetryableFailureWithoutAcknowledgingAPartialReceipt() throws Exception {
        var p=pending(Action.AUTHORIZE); byte[] body=bytes(event(p.response())); var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var owner=executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                operations.source(A,payment,true); locked.countDown();
                try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            try { assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue(); callback(body).andExpect(status().isServiceUnavailable()); assertThat(count("provider_webhook_receipts")).isZero(); }
            finally { release.countDown(); } owner.get(10,TimeUnit.SECONDS);
        }
        callback(body).andExpect(status().isOk()); state("AUTHORIZED",1);
    }
    @Test void databaseRequiresFinalReceiptsAndProtectsEvidenceAndUnknownApplication() throws Exception {
        var p=pending(Action.AUTHORIZE); var e=event(p.response()); byte[] body=bytes(e);
        var verified=new Verified(e,body,KEY_ID,Instant.now().getEpochSecond());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(s -> receipts.claim(UUID.randomUUID(),verified,p.operation())))
                .hasStackTraceContaining("Webhook receipt must finish");
        assertThat(count("provider_webhook_receipts")).isZero();
        var stored=operations.byId(A,p.operation(),false).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(s -> operations.complete(stored,p.response(),false)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        accept(e);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM provider_webhook_receipts")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE provider_webhook_receipts SET key_id='rewritten'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        state("AUTHORIZED",1);
    }
    @Test void receiptMetadataIsOwnedBoundedAndOmitsRawBodiesAndSecrets() throws Exception {
        var p=pending(Action.AUTHORIZE); accept(event(p.response())); accept(new Event(1,UUID.randomUUID(),p.response()));
        var first=mvc.perform(get(ownedPage(p.operation())).queryParam("limit","1").with(httpBasic("webhook-merchant-a",PASSWORD))).andExpect(status().isOk()).andReturn();
        var page=mapper.readValue(first.getResponse().getContentAsString(),com.fincore.payments.dto.ProviderWebhook.Page.class);
        assertThat(page.receipts()).hasSize(1); assertThat(page.nextAfterReceiptId()).isNotNull();
        assertThat(first.getResponse().getContentAsString()).doesNotContain(KEY,API_KEY,PASSWORD,"raw_body","rawBody","WEBHOOK-PRIVATE-ORDER");
        mvc.perform(get(ownedPage(p.operation())).with(httpBasic("webhook-merchant-b",PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(ownedPage(p.operation()))).andExpect(status().isUnauthorized());
        mvc.perform(get(ownedPage(p.operation())).queryParam("limit","101").with(httpBasic("webhook-merchant-a",PASSWORD))).andExpect(status().isBadRequest());
    }
    @Test void machineAuthenticationDoesNotUseMerchantBasicOrDisableMerchantCsrf() throws Exception {
        var p=pending(Action.AUTHORIZE); byte[] body=bytes(event(p.response()));
        mvc.perform(post(ProviderWebhookWire.PATH).with(httpBasic("webhook-merchant-a",PASSWORD)).contentType(APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/"+payment+"/capture").with(httpBasic("webhook-merchant-a",PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get(ProviderWebhookWire.PATH)).andExpect(status().isForbidden());
        assertThat(count("provider_webhook_receipts")).isZero(); state("CREATED",0);
    }
    @ParameterizedTest @ValueSource(strings={"signature","timestamp","bytes","instance","json"})
    void authenticationAndStrictProtocolFailuresNeverWriteReceipts(String field) throws Exception {
        var p=pending(Action.AUTHORIZE); String json=ProviderWire.mapper().writeValueAsString(event(p.response()));
        if (field.equals("instance")) json=json.replace(INSTANCE.toString(),UUID.randomUUID().toString());
        if (field.equals("json")) json=json.replaceFirst("\\{","{\"unexpected\":true,");
        byte[] body=json.getBytes(java.nio.charset.StandardCharsets.UTF_8); String time=Long.toString(Instant.now().getEpochSecond()-(field.equals("timestamp") ? 1000 : 0));
        String signature=ProviderWebhookWire.signature(KEY,KEY_ID,time,body);
        if (field.equals("signature")) signature="v1="+"0".repeat(64);
        if (field.equals("bytes")) body=(json+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post(ProviderWebhookWire.PATH).contentType(APPLICATION_JSON).content(body).header(ProviderWebhookWire.TIMESTAMP,time)
                .header(ProviderWebhookWire.KEY_ID,KEY_ID).header(ProviderWebhookWire.SIGNATURE,signature))
                .andExpect(status().is(field.equals("json") ? 400 : 401));
        assertThat(count("provider_webhook_receipts")).isZero(); state("CREATED",0);
    }
    @Test void realSandboxCallbackResolvesALostHttpReplyThroughTheActualServletReceiver() throws Exception {
        var release=new CountDownLatch(1); var entered=new CountDownLatch(1);
        var sender=spy(new SandboxWebhookSender(URI.create("http://127.0.0.1:"+apiPort+ProviderWebhookWire.PATH),KEY,KEY_ID));
        doAnswer(call -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); }).when(sender).deliver(any());
        try (var remote=new SandboxProviderServer(0,Files.createTempDirectory("fincore-auto-webhook-"),INSTANCE,API_KEY,sender);
             var remoteAdapter=new HttpSandboxPaymentProvider(new ProviderProperties(true,URI.create("http://127.0.0.1:"+remote.port()),API_KEY,INSTANCE,2000,30))) {
            doAnswer(call -> remoteAdapter.execute(call.getArgument(0))).when(adapterTarget).execute(any());
            var command=new Command(UUID.randomUUID(),Action.AUTHORIZE,Scenario.TIMEOUT_AFTER_ACCEPT);
            var result=mvc.perform(post("/api/v1/payments/"+payment+"/provider-operations").with(httpBasic("webhook-merchant-a",PASSWORD)).with(csrf())
                    .contentType(APPLICATION_JSON).content(mapper.writeValueAsString(command))).andExpect(status().isOk()).andReturn();
            assertThat(mapper.readValue(result.getResponse().getContentAsString(),Result.class).operation().state()).isEqualTo(State.UNKNOWN);
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); release.countDown();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> state("AUTHORIZED",1));
            assertThat(count("provider_webhook_receipts")).isEqualTo(1); assertThat(remote.decisionCount()).isEqualTo(1);
        } finally { release.countDown(); }
    }
    @Test void outboundDisablementDoesNotDiscardAnEnabledVerifiedCallback() throws Exception {
        var p=pending(Action.AUTHORIZE); doReturn(false).when(providerProperties).enabled();
        assertThat(accept(event(p.response())).disposition()).isEqualTo(Disposition.CONFIRMED); state("AUTHORIZED",1);
    }
    @Test void disabledReceiverRefusesEvenAnOtherwiseValidDeliveryWithoutWrites() throws Exception {
        var p=pending(Action.AUTHORIZE); doReturn(false).when(webhookProperties).enabled();
        callback(bytes(event(p.response()))).andExpect(status().isServiceUnavailable());
        state("CREATED",0); assertThat(count("provider_webhook_receipts")).isZero();
    }
    @Test void detectedSourceDriftRetainsTheConfirmationForReviewWithoutFinancialApplication() throws Exception {
        var p=pending(Action.AUTHORIZE); var original=operations.source(A,payment,false);
        doReturn(new ProviderOperationRepository.Source(payment,A,original.version()+1,original.status(),original.amount(),original.currency()))
                .when(operations).source(A,payment,true);
        assertThat(accept(event(p.response())).disposition()).isEqualTo(Disposition.REQUIRES_REVIEW);
        assertThat(operations.byId(A,p.operation(),false).orElseThrow().view().state()).isEqualTo(State.REQUIRES_REVIEW);
        state("CREATED",0); assertThat(count("ledger_transactions")).isZero(); assertThat(count("payment_outbox")).isEqualTo(1);
        assertThat(accept(event(p.response())).duplicate()).isTrue();
    }
    @Test void v17UpgradePreservesExistingProviderIntentAndFinancialBeforeImages() throws Exception {
        String schema="webhook_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try (var connection=jdbc.getDataSource().getConnection()) {
            String original=connection.getSchema(); var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var sql=new JdbcTemplate(ds); sql.execute("CREATE SCHEMA \""+schema+"\"");
            try {
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("17").load().migrate();
                connection.setSchema(schema); UUID id=UUID.randomUUID(); var time=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                sql.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES (?,'upgrade-webhook-merchant',?,true)",A,HASH);
                var data=new PaymentResponse(id,"WEBHOOK-UPGRADE",new BigDecimal("10.00"),"INR",com.fincore.payments.domain.PaymentStatus.CREATED,time,time);
                var event=new PaymentEvent(UUID.randomUUID(),com.fincore.payments.domain.PaymentEventType.PAYMENT_CREATED,1,id,0,time,data);
                sql.update("INSERT INTO payments(id,merchant_id,merchant_reference,amount,currency,status,created_at,updated_at,version) VALUES (?,?,'WEBHOOK-UPGRADE',10,'INR','CREATED',?,?,0)",id,A,java.sql.Timestamp.from(time),java.sql.Timestamp.from(time));
                sql.update("INSERT INTO idempotency_records(merchant_id,idempotency_key,request_fingerprint,payment_id,response_body,created_at) VALUES (?,'webhook-upgrade-key',?,?,?,?)",A,"0".repeat(64),id,mapper.writeValueAsString(data),java.sql.Timestamp.from(time));
                sql.update("INSERT INTO payment_outbox(event_id,payment_id,payment_version,event_type,occurred_at,payload) VALUES (?,?,0,'PAYMENT_CREATED',?,?)",event.eventId(),id,java.sql.Timestamp.from(time),mapper.writeValueAsString(event));
                var local=new ProviderOperationTransactions(new ProviderOperationRepository(sql),payments,providerProperties);
                var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                tx.executeWithoutResult(s -> local.prepare(A,id,new Command(UUID.randomUUID(),Action.AUTHORIZE,Scenario.APPROVE)));
                var before=new TreeMap<String,String>();
                for (String table:List.of("payments","merchants","idempotency_records","payment_outbox","payment_provider_bindings","payment_provider_operations"))
                    before.put(table,sql.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "+table+" t",String.class));
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate(); connection.setSchema(schema);
                for (var row:before.entrySet()) assertThat(sql.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "+row.getKey()+" t",String.class)).isEqualTo(row.getValue());
                assertThat(sql.queryForObject("SELECT count(*) FROM provider_webhook_receipts",Long.class)).isZero();
            } finally { connection.setSchema(original); sql.execute("DROP SCHEMA \""+schema+"\" CASCADE"); }
        }
    }
}
