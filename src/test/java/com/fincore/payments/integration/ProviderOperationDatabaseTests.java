package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.ProviderProperties;
import com.fincore.payments.domain.*;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.ProviderOperation.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.provider.*;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
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

/** Real database + real loopback HTTP provider + real merchant credentials and CSRF. */
@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ProviderOperationDatabaseTests {
    static final UUID INSTANCE=UUID.randomUUID();
    static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111117"),B=UUID.fromString("22222222-2222-4222-8222-222222222217");
    static final String PASSWORD="provider-merchant-test-password",KEY="test-only-provider-secret-0123456789";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    static SandboxProviderServer sandbox;
    static Path journal;
    static synchronized SandboxProviderServer sandbox() {
        if (sandbox==null) try {
            journal=Files.createTempDirectory("fincore-provider-it-");
            sandbox=new SandboxProviderServer(0,journal,INSTANCE,KEY);
        } catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
        return sandbox;
    }
    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) {
        int port=sandbox().port();
        registry.add("fincore.provider.enabled",() -> true);
        registry.add("fincore.provider.base-url",() -> "http://127.0.0.1:"+port);
        registry.add("fincore.provider.instance-id",() -> INSTANCE.toString());
        registry.add("fincore.provider.api-key",() -> KEY);
        registry.add("fincore.provider.timeout-millis",() -> 2000);
        registry.add("fincore.provider.lease-seconds",() -> 5);
    }
    @AfterAll static void stopSandbox() throws Exception { if (sandbox!=null) { sandbox.close(); sandbox=null; } }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired ProviderOperationTransactions decisions;
    @MockitoSpyBean ProviderOperationRepository operations;
    @Autowired ProviderProperties properties;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean HttpSandboxPaymentProvider adapter;
    @MockitoSpyBean OutboxService outbox;
    HttpSandboxPaymentProvider adapterTarget;
    OutboxService outboxTarget;
    UUID payment;

    @BeforeEach void setup() {
        adapterTarget=AopTestUtils.getUltimateTargetObject(adapter);
        outboxTarget=AopTestUtils.getUltimateTargetObject(outbox);
        jdbc.execute("""
            TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,
                projection_preservation_approvals,projection_preservations,projection_purge_plans,projection_routes,projection_route_audit,
                projection_rebuild_items,projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,
                consumer_event_failures,consumer_event_positions,outbox_requeue_audit,payment_event_projections,consumed_payment_events,
                local_payment_event_inbox,payment_outbox,ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments
            """);
        for (var account:List.of(Map.entry(A,"provider-merchant-a"),Map.entry(B,"provider-merchant-b")))
            jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES (?,?,?,true) ON CONFLICT (id) DO UPDATE SET password_hash=EXCLUDED.password_hash,enabled=true",
                    account.getKey(),account.getValue(),HASH);
        payment=payments.createPayment(A,"provider-source-"+UUID.randomUUID(),new CreatePaymentRequest("PRIVATE-PROVIDER-ORDER",BigDecimal.TEN,"INR")).payment().id();
    }

    @Test void confirmedFullLifecyclePostsFinancialEffectsOnceAndKeepsHistoricalRetries() throws Exception {
        var auth=command(Action.AUTHORIZE,Scenario.APPROVE);
        var authorize=submit(payment,auth);
        assertThat(authorize.operation().state()).isEqualTo(State.APPLIED);
        assertThat(authorize.operation().providerOutcome()).isEqualTo(Outcome.APPROVED);
        assertThat(authorize.operation().providerPaymentId()).isEqualTo("sandbox-pay-"+payment);
        assertStatus("AUTHORIZED",1);
        var capture=command(Action.CAPTURE,Scenario.APPROVE);
        var captured=submit(payment,capture); assertThat(captured.operation().localEffectApplied()).isTrue();
        assertStatus("CAPTURED",2); assertThat(count("ledger_transactions")).isEqualTo(1); assertThat(count("ledger_entries")).isEqualTo(2);
        var refund=command(Action.REFUND,Scenario.APPROVE);
        submit(payment,refund); assertStatus("REFUNDED",3);
        assertThat(count("ledger_transactions")).isEqualTo(2); assertThat(count("ledger_entries")).isEqualTo(4);
        assertThat(count("payment_outbox")).isEqualTo(4);
        assertThat(submit(payment,auth).operation()).isEqualTo(authorize.operation());
        var replay=submit(payment,capture); assertThat(replay.replayed()).isTrue(); assertThat(replay.operation()).isEqualTo(captured.operation());
        assertThat(count("payment_provider_operations")).isEqualTo(3);
    }
    @Test void providerDeclinedAuthorizationFailsLocallyWithoutInventingLedgerEntries() throws Exception {
        var result=submit(payment,command(Action.AUTHORIZE,Scenario.DECLINE));
        assertThat(result.operation().state()).isEqualTo(State.DECLINED); assertThat(result.operation().localEffectApplied()).isTrue();
        assertStatus("FAILED",1); assertThat(count("ledger_transactions")).isZero(); assertThat(count("payment_outbox")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"CAPTURE","REFUND"})
    void declinedFinancialOperationDoesNotAdvanceLocalStateOrPostMoney(String value) throws Exception {
        submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE));
        Action action=Action.valueOf(value);
        if (action==Action.REFUND) submit(payment,command(Action.CAPTURE,Scenario.APPROVE));
        long journals=count("ledger_transactions"),events=count("payment_outbox");
        var result=submit(payment,command(action,Scenario.DECLINE));
        assertThat(result.operation().state()).isEqualTo(State.DECLINED); assertThat(result.operation().localEffectApplied()).isFalse();
        assertThat(count("ledger_transactions")).isEqualTo(journals); assertThat(count("payment_outbox")).isEqualTo(events);
        assertStatus(action==Action.CAPTURE ? "AUTHORIZED" : "CAPTURED",action==Action.CAPTURE ? 1 : 2);
        assertThat(submit(payment,command(action,Scenario.APPROVE)).operation().state()).isEqualTo(State.APPLIED);
    }
    @Test void acceptedButLostReplyRetainsUnknownThenRecoversWithoutDuplicateProviderOrLocalEffects() throws Exception {
        int before=sandbox().decisionCount();
        var intent=command(Action.AUTHORIZE,Scenario.TIMEOUT_AFTER_ACCEPT);
        var first=submit(payment,intent);
        assertThat(first.operation().state()).isEqualTo(State.UNKNOWN); assertThat(first.operation().localEffectApplied()).isFalse();
        assertThat(first.operation().errorCode()).isEqualTo("PROVIDER_OUTCOME_UNCONFIRMED");
        assertStatus("CREATED",0); assertThat(sandbox().decisionCount()).isEqualTo(before+1);
        var retried=submit(payment,intent); assertThat(retried.replayed()).isTrue(); assertThat(retried.operation().attempts()).isEqualTo(2);
        assertThat(retried.operation().operationId()).isEqualTo(first.operation().operationId());
        assertStatus("AUTHORIZED",1); assertThat(sandbox().decisionCount()).isEqualTo(before+1); assertThat(count("payment_outbox")).isEqualTo(2);
    }
    @Test void captureReplyLossDoesNotPostMoneyUntilTheExistingOperationIsConfirmed() throws Exception {
        submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE));
        var capture=command(Action.CAPTURE,Scenario.TIMEOUT_AFTER_ACCEPT);
        var first=submit(payment,capture); assertThat(first.operation().state()).isEqualTo(State.UNKNOWN);
        assertStatus("AUTHORIZED",1); assertThat(count("ledger_transactions")).isZero();
        submit(payment,capture); assertStatus("CAPTURED",2); assertThat(count("ledger_transactions")).isEqualTo(1);
        submit(payment,capture); assertThat(count("ledger_transactions")).isEqualTo(1);
    }
    @Test void providerNetworkCallNeverHoldsAnApplicationTransaction() throws Exception {
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); })
                .when(adapterTarget).execute(any());
        assertThat(submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)).operation().state()).isEqualTo(State.APPLIED);
    }
    @Test void inheritedRepeatableReadTransactionIsSuspendedAndCannotRollBackProviderCompletion() throws Exception {
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); })
                .when(adapterTarget).execute(any());
        var caller=new TransactionTemplate(manager);
        caller.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        caller.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment)).isEqualTo("CREATED");
            try { assertThat(submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)).operation().state()).isEqualTo(State.APPLIED); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment)).isEqualTo("CREATED");
            status.setRollbackOnly();
        });
        assertStatus("AUTHORIZED",1);
        assertThat(count("payment_provider_operations")).isEqualTo(1);
    }
    @Test void uncertainTransportDoesNotBecomeADeclineAndTheSameIdentityCanRetry() throws Exception {
        doThrow(new ProviderUncertainException()).when(adapterTarget).execute(any());
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE);
        var unknown=submit(payment,intent); assertThat(unknown.operation().state()).isEqualTo(State.UNKNOWN); assertStatus("CREATED",0);
        doCallRealMethod().when(adapterTarget).execute(any());
        assertThat(submit(payment,intent).operation().operationId()).isEqualTo(unknown.operation().operationId()); assertStatus("AUTHORIZED",1);
    }
    @Test void mismatchedProviderMetadataIsUnknownAndNeverApplied() throws Exception {
        doAnswer(call -> {
            Request r=call.getArgument(0);
            return new Response(1,INSTANCE,r.operationId(),UUID.randomUUID(),r.merchantId(),r.action(),r.amount(),r.currency(),Outcome.APPROVED,
                    "sandbox-pay-"+r.paymentId(),"sandbox-op-"+r.operationId());
        }).when(adapterTarget).execute(any());
        assertThat(submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)).operation().state()).isEqualTo(State.UNKNOWN);
        assertStatus("CREATED",0); assertThat(count("ledger_transactions")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"authorize","capture","fail","refund"})
    void manualTransitionCannotBypassAManagedPayment(String action) throws Exception {
        doThrow(new ProviderUncertainException()).when(adapterTarget).execute(any());
        submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)); var before=image();
        mvc.perform(post("/api/v1/payments/"+payment+"/"+action).with(httpBasic("provider-merchant-a",PASSWORD)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PROVIDER_OPERATION_CONFLICT"));
        assertThat(image()).isEqualTo(before);
    }
    @Test void databaseGuardRejectsUnconfirmedManagedStateChanges() throws Exception {
        decisions.prepare(A,payment,command(Action.AUTHORIZE,Scenario.APPROVE));
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status='AUTHORIZED',version=version+1 WHERE id=?",payment)).isInstanceOf(RuntimeException.class);
        assertStatus("CREATED",0);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> payments.applyProviderResult(payment,UUID.randomUUID(),Action.AUTHORIZE,Outcome.APPROVED)))
                .isInstanceOf(ProviderOperationConflictException.class);
    }
    @Test void requestUuidCannotChangeActionScenarioOrPayment() throws Exception {
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE); submit(payment,intent);
        for (var changed:List.of(new Command(intent.requestId(),Action.CAPTURE,Scenario.APPROVE),new Command(intent.requestId(),Action.AUTHORIZE,Scenario.DECLINE)))
            request(payment,changed,"provider-merchant-a").andExpect(status().isConflict());
        UUID another=payments.createPayment(A,"other-"+UUID.randomUUID(),new CreatePaymentRequest("OTHER",BigDecimal.TEN,"INR")).payment().id();
        request(another,intent,"provider-merchant-a").andExpect(status().isConflict());
        assertThat(count("payment_provider_bindings")).isEqualTo(1);
    }
    @Test void differentMerchantsCanUseTheSameRequestUuidWithoutSharingDecisions() throws Exception {
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE); var first=submit(payment,intent);
        UUID other=payments.createPayment(B,"other-"+UUID.randomUUID(),new CreatePaymentRequest("OTHER",BigDecimal.TEN,"INR")).payment().id();
        var result=request(other,intent,"provider-merchant-b").andExpect(status().isOk()).andReturn();
        var second=mapper.readValue(result.getResponse().getContentAsString(),Result.class);
        assertThat(second.operation().operationId()).isNotEqualTo(first.operation().operationId());
    }
    @Test void foreignCommandsReadsAndListsAreRefusedBeforeProviderTraffic() throws Exception {
        var original=submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)); int before=sandbox().decisionCount();
        request(payment,command(Action.CAPTURE,Scenario.APPROVE),"provider-merchant-b").andExpect(status().isNotFound());
        for (String suffix:List.of("","/"+original.operation().operationId()))
            mvc.perform(get(base(payment)+suffix).with(httpBasic("provider-merchant-b",PASSWORD))).andExpect(status().isNotFound());
        assertThat(sandbox().decisionCount()).isEqualTo(before); assertThat(count("payment_provider_operations")).isEqualTo(1);
    }
    @Test void authenticationAndActualPostCsrfBoundaryAreRetained() throws Exception {
        mvc.perform(get(base(payment))).andExpect(status().isUnauthorized());
        mvc.perform(post(base(payment)).with(httpBasic("provider-merchant-a",PASSWORD)).contentType(APPLICATION_JSON)
                .content(mapper.writeValueAsString(command(Action.AUTHORIZE,Scenario.APPROVE)))).andExpect(status().isForbidden());
        assertThat(count("payment_provider_operations")).isZero();
    }
    @Test void pendingOperationRejectsANewIdentityButNotAnExactRetry() throws Exception {
        doThrow(new ProviderUncertainException()).when(adapterTarget).execute(any());
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE); var original=submit(payment,intent);
        request(payment,command(Action.AUTHORIZE,Scenario.APPROVE),"provider-merchant-a").andExpect(status().isConflict());
        assertThat(submit(payment,intent).operation().operationId()).isEqualTo(original.operation().operationId());
        assertThat(count("payment_provider_operations")).isEqualTo(1);
    }
    @Test void disabledOrChangedProviderConfigurationDoesNotRewriteAcceptedIntent() throws Exception {
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE); var original=submit(payment,intent);
        var disabled=new ProviderOperationTransactions(operations,payments,new ProviderProperties(false,properties.baseUrl(),"",new UUID(0,0),2000,5));
        var tx=new TransactionTemplate(manager);
        assertThat(tx.execute(status -> disabled.prepare(A,payment,intent)).operation().view()).isEqualTo(original.operation());
        var historical=tx.execute(status -> disabled.claim(A,original.operation().operationId()));
        assertThat(historical).isNull();
        UUID fresh=payments.createPayment(A,"fresh-"+UUID.randomUUID(),new CreatePaymentRequest("FRESH",BigDecimal.TEN,"INR")).payment().id();
        assertThatThrownBy(() -> tx.execute(status -> disabled.prepare(A,fresh,command(Action.AUTHORIZE,Scenario.APPROVE))))
                .isInstanceOf(ProviderOperationConflictException.class);
        var pending=decisions.prepare(A,fresh,command(Action.AUTHORIZE,Scenario.APPROVE));
        var changed=new ProviderOperationTransactions(operations,payments,new ProviderProperties(true,properties.baseUrl(),KEY,UUID.randomUUID(),2000,5));
        assertThatThrownBy(() -> tx.execute(status -> changed.claim(A,pending.operation().view().operationId())))
                .isInstanceOf(ProviderOperationConflictException.class);
        assertThat(operations.byId(A,pending.operation().view().operationId(),false).orElseThrow().view().attempts()).isZero();
    }
    @Test void noProviderCaptureCanAdoptAManuallyAuthorizedPayment() throws Exception {
        payments.authorizePayment(payment);
        request(payment,command(Action.CAPTURE,Scenario.APPROVE),"provider-merchant-a").andExpect(status().isConflict());
        assertThat(count("payment_provider_bindings")).isZero(); assertThat(count("payment_provider_operations")).isZero();
    }
    @Test void acceptedCommandSurvivesAWorkerCrashBeforeAnyProviderCall() throws Exception {
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE); var prepared=decisions.prepare(A,payment,intent);
        var lostWorker=decisions.claim(A,prepared.operation().view().operationId()); assertThat(lostWorker).isNotNull();
        assertThat(submit(payment,intent).operation().state()).isEqualTo(State.IN_FLIGHT);
        expireLease(prepared.operation().view().operationId());
        var recovered=submit(payment,intent); assertThat(recovered.operation().attempts()).isEqualTo(2); assertStatus("AUTHORIZED",1);
    }
    @Test void staleWorkerCompletionCannotOverwriteANewerLeaseOrRepeatLocalEffects() throws Exception {
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE); var prepared=decisions.prepare(A,payment,intent);
        UUID id=prepared.operation().view().operationId();
        var first=decisions.claim(A,id); Response response=adapter.execute(first.operation().request());
        expireLease(id); var newer=decisions.claim(A,id);
        assertThat(decisions.finish(A,payment,id,first.token(),response).state()).isEqualTo(State.IN_FLIGHT); assertStatus("CREATED",0);
        assertThat(decisions.finish(A,payment,id,newer.token(),response).state()).isEqualTo(State.APPLIED); assertStatus("AUTHORIZED",1);
        assertThat(decisions.finish(A,payment,id,first.token(),response).state()).isEqualTo(State.APPLIED); assertThat(count("payment_outbox")).isEqualTo(2);
    }
    @Test void concurrentExactSubmissionsHaveOneActiveProviderCall() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); }).when(adapterTarget).execute(any());
        var intent=command(Action.AUTHORIZE,Scenario.APPROVE);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> submit(payment,intent));
            try { assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); assertThat(submit(payment,intent).operation().state()).isEqualTo(State.IN_FLIGHT); }
            finally { release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS).operation().state()).isEqualTo(State.APPLIED);
        }
        assertThat(count("payment_provider_operations")).isEqualTo(1); assertStatus("AUTHORIZED",1);
    }
    @Test void lateLocalFailureLeavesTheProviderDecisionRecoverableWithoutDoublePosting() throws Exception {
        submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)); int providerBefore=sandbox().decisionCount();
        var capture=command(Action.CAPTURE,Scenario.APPROVE);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("simulated late provider application failure"); }).when(outboxTarget).record(any());
        request(payment,capture,"provider-merchant-a").andExpect(status().isInternalServerError());
        assertStatus("AUTHORIZED",1); assertThat(count("ledger_transactions")).isZero(); assertThat(count("payment_outbox")).isEqualTo(2);
        assertThat(sandbox().decisionCount()).isEqualTo(providerBefore+1);
        doCallRealMethod().when(outboxTarget).record(any());
        UUID id=operations.byRequest(A,capture.requestId()).orElseThrow().view().operationId(); expireLease(id);
        assertThat(submit(payment,capture).operation().state()).isEqualTo(State.APPLIED); assertStatus("CAPTURED",2);
        assertThat(count("ledger_transactions")).isEqualTo(1); assertThat(sandbox().decisionCount()).isEqualTo(providerBefore+1);
    }
    @Test void databasePreventsFinalMetadataRewriteBindingDeletionAndUnauditedApplication() throws Exception {
        var result=submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)); UUID id=result.operation().operationId();
        for (String sql:List.of("UPDATE payment_provider_operations SET sandbox_scenario='DECLINE' WHERE operation_id=?",
                "DELETE FROM payment_provider_operations WHERE operation_id=?","UPDATE payment_provider_operations SET state='UNKNOWN' WHERE operation_id=?"))
            assertThatThrownBy(() -> jdbc.update(sql,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_provider_bindings WHERE payment_id=?",payment))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_provider_bindings SET provider_payment_id='' WHERE payment_id=?",payment))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var capture=decisions.prepare(A,payment,command(Action.CAPTURE,Scenario.APPROVE)); var claim=decisions.claim(A,capture.operation().view().operationId());
        var response=adapter.execute(claim.operation().request());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> operations.complete(claim.operation(),response,false)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertStatus("AUTHORIZED",1); assertThat(count("ledger_transactions")).isZero();
    }
    @Test void ownedProgressPagingIsBoundedAndSanitized() throws Exception {
        var auth=submit(payment,command(Action.AUTHORIZE,Scenario.APPROVE)); submit(payment,command(Action.CAPTURE,Scenario.APPROVE));
        var first=mvc.perform(get(base(payment)).queryParam("limit","1").with(httpBasic("provider-merchant-a",PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        var page=mapper.readValue(first.getResponse().getContentAsString(),Page.class); assertThat(page.operations()).hasSize(1); assertThat(page.nextAfterOperationId()).isNotNull();
        mvc.perform(get(base(payment)).queryParam("limit","101").with(httpBasic("provider-merchant-a",PASSWORD))).andExpect(status().isBadRequest());
        var result=mvc.perform(get(base(payment)+"/"+auth.operation().operationId()).with(httpBasic("provider-merchant-a",PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(KEY,PASSWORD,"leaseToken","PRIVATE-PROVIDER-ORDER","Authorization");
    }

    @Test void readOnlyProgressRetainsOneSnapshotWhileACompletionCommits() throws Exception {
        var prepared=decisions.prepare(A,payment,command(Action.AUTHORIZE,Scenario.APPROVE)); UUID id=prepared.operation().view().operationId();
        var claim=decisions.claim(A,id); var response=adapter.execute(claim.operation().request());
        var snapshot=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("provider-review-snapshot")) {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
                assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
                snapshot.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(operations).byId(eq(A),eq(id),eq(false));
        try (var executor=Executors.newSingleThreadExecutor(r -> new Thread(r,"provider-review-snapshot"))) {
            var report=executor.submit(() -> decisions.get(A,payment,id));
            try {
                assertThat(snapshot.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(decisions.finish(A,payment,id,claim.token(),response).state()).isEqualTo(State.APPLIED);
            } finally { release.countDown(); }
            assertThat(report.get(10,TimeUnit.SECONDS).state()).isEqualTo(State.IN_FLIGHT);
        }
        assertThat(decisions.get(A,payment,id).state()).isEqualTo(State.APPLIED);
    }
    @Test void lockedSourceRefusesNewWorkWithoutPartialBindingOrOperation() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var owner=executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                operations.source(A,payment,true); locked.countDown();
                try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                request(payment,command(Action.AUTHORIZE,Scenario.APPROVE),"provider-merchant-a").andExpect(status().isConflict());
                assertThat(count("payment_provider_bindings")).isZero(); assertThat(count("payment_provider_operations")).isZero();
            } finally { release.countDown(); }
            owner.get(10,TimeUnit.SECONDS);
        }
    }
    @Test void v16UpgradePreservesFinancialAndRetryRowsAndStartsWithNoProviderBindings() throws Exception {
        String schema="provider_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var dsRoot=jdbc.getDataSource();
        try (var connection=dsRoot.getConnection()) {
            String original=connection.getSchema();
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var sql=new JdbcTemplate(ds);
            sql.execute("CREATE SCHEMA \""+schema+"\"");
            try {
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("16").load().migrate();
                connection.setSchema(schema);
                sql.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES (?,'upgrade-merchant',?,true)",A,HASH);
                UUID id=UUID.randomUUID(); var time=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                var data=new PaymentResponse(id,"UPGRADE-PAYMENT",BigDecimal.TEN,"INR",PaymentStatus.CREATED,time,time);
                var event=new PaymentEvent(UUID.randomUUID(),PaymentEventType.PAYMENT_CREATED,1,id,0,time,data);
                sql.update("INSERT INTO payments(id,merchant_id,merchant_reference,amount,currency,status,created_at,updated_at,version) VALUES (?,?,'UPGRADE-PAYMENT',10,'INR','CREATED',?,?,0)",
                        id,A,java.sql.Timestamp.from(time),java.sql.Timestamp.from(time));
                sql.update("INSERT INTO idempotency_records(merchant_id,idempotency_key,request_fingerprint,payment_id,response_body,created_at) VALUES (?,'upgrade-key',?,?,?,?)",
                        A,"0".repeat(64),id,mapper.writeValueAsString(data),java.sql.Timestamp.from(time));
                sql.update("INSERT INTO payment_outbox(event_id,payment_id,payment_version,event_type,occurred_at,payload) VALUES (?,?,0,'PAYMENT_CREATED',?,?)",
                        event.eventId(),id,java.sql.Timestamp.from(time),mapper.writeValueAsString(event));
                var before=new TreeMap<String,String>();
                for (String table:List.of("payments","idempotency_records","payment_outbox","merchants"))
                    before.put(table,sql.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "+table+" t",String.class));
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                for (var row:before.entrySet())
                    assertThat(sql.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "+row.getKey()+" t",String.class)).isEqualTo(row.getValue());
                assertThat(sql.queryForObject("SELECT count(*) FROM payment_provider_operations",Long.class)).isZero();
                assertThat(sql.queryForObject("SELECT count(*) FROM payment_provider_bindings",Long.class)).isZero();
                var local=new ProviderOperationTransactions(new ProviderOperationRepository(sql),payments,properties);
                var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                var planned=tx.execute(status -> local.prepare(A,id,command(Action.AUTHORIZE,Scenario.APPROVE)));
                assertThat(planned.operation().view().state()).isEqualTo(State.READY);
            } finally { connection.setSchema(original); sql.execute("DROP SCHEMA \""+schema+"\" CASCADE"); }
        }
    }

    private static String base(UUID id) { return "/api/v1/payments/"+id+"/provider-operations"; }
    private static Command command(Action action,Scenario scenario) { return new Command(UUID.randomUUID(),action,scenario); }
    private org.springframework.test.web.servlet.ResultActions request(UUID id,Command command,String username) throws Exception {
        return mvc.perform(post(base(id)).with(httpBasic(username,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON).content(mapper.writeValueAsString(command)));
    }
    private Result submit(UUID id,Command command) throws Exception {
        var result=request(id,command,"provider-merchant-a").andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(),Result.class);
    }
    private void assertStatus(String status,long version) {
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?",Long.class,payment)).isEqualTo(version);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private void expireLease(UUID operation) {
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(8)).until(() ->
                Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until<=clock_timestamp() FROM payment_provider_operations WHERE operation_id=?",Boolean.class,operation)));
    }
    private Map<String,String> image() {
        var result=new TreeMap<String,String>();
        for (String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename<>'flyway_schema_history'",String.class))
            result.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM "+table+" t",String.class));
        return result;
    }
}
