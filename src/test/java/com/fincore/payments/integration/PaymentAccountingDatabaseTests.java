package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.domain.*;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.PaymentAccounting.*;
import com.fincore.payments.exception.PaymentAccountingException;
import com.fincore.payments.repository.LedgerRepository;
import com.fincore.payments.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false",
    "fincore.merchant-webhooks.enabled=true","fincore.merchant-webhooks.dispatch-enabled=false",
    "fincore.merchant-webhooks.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "fincore.merchant-webhooks.allowed-destinations=http://127.0.0.1:18082/webhooks",
    "fincore.provider.enabled=true","fincore.provider.api-key=amount-test-only-provider-api-key-12345",
    "fincore.provider.instance-id=33333333-3333-4333-8333-333333333320"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PaymentAccountingDatabaseTests {
    static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111120"),B=UUID.fromString("22222222-2222-4222-8222-222222222220");
    static final String PASSWORD="accounting-test-password",USER="accounting-merchant-a";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired PaymentAccountingService accounting;
    @Autowired ProviderOperationTransactions provider;
    @Autowired PaymentEventConsumer consumer;
    @Autowired MerchantWebhookService subscriptions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean OutboxService outbox;
    @MockitoSpyBean LedgerRepository ledger;
    UUID payment;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans,projection_routes,projection_route_audit,projection_rebuild_items,projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,consumer_event_failures,consumer_event_positions,outbox_requeue_audit,payment_event_projections,consumed_payment_events,local_payment_event_inbox,payment_outbox,ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments");
        for(var entry:List.of(Map.entry(A,USER),Map.entry(B,"accounting-merchant-b")))
            jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true) ON CONFLICT(id) DO UPDATE SET password_hash=EXCLUDED.password_hash,enabled=true",entry.getKey(),entry.getValue(),HASH);
        payment=payments.createPayment(A,"accounting-"+UUID.randomUUID(),new CreatePaymentRequest("AMOUNT-ORDER",new BigDecimal("100.00"),"INR")).payment().id();
        payments.authorizePayment(payment);
    }
    @AfterEach void resetSpies() { reset(AopTestUtils.getUltimateTargetObject(outbox),AopTestUtils.getUltimateTargetObject(ledger)); SecurityContextHolder.clearContext(); }
    <T> T as(UUID merchant,Supplier<T> work) {
        var before=SecurityContextHolder.getContext(); var context=SecurityContextHolder.createEmptyContext();
        var principal=new MerchantPrincipal(merchant,merchant.equals(A)?USER:"accounting-merchant-b",HASH,true);
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",principal.getAuthorities()));
        SecurityContextHolder.setContext(context); try { return work.get(); } finally { SecurityContextHolder.setContext(before); }
    }
    Command capture(long version,String amount,String fee) { return new Command(UUID.randomUUID(),version,Action.CAPTURE,new BigDecimal(amount),new BigDecimal(fee),null); }
    Command refund(long version,String amount,UUID capture) { return new Command(UUID.randomUUID(),version,Action.REFUND,new BigDecimal(amount),BigDecimal.ZERO,capture); }
    Result submit(Command command) { return as(A,()->accounting.submit(payment,command)); }
    Snapshot view() { return as(A,()->accounting.get(payment)); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    void refused(Command command) { assertThatThrownBy(()->submit(command)).isInstanceOf(PaymentAccountingException.class); }

    @Test void splitCaptureRefundAndRetainedFeesBalanceAndEnrollEveryEvent() {
        as(A,()->subscriptions.create(new MerchantWebhook.Create(UUID.randomUUID(),"http://127.0.0.1:18082/webhooks")));
        var first=submit(capture(1,"60","3")); var second=submit(capture(2,"40","2"));
        assertThat(second.accounting().status()).isEqualTo(PaymentStatus.CAPTURED);
        submit(refund(3,"20",first.operation().operationId()));
        submit(refund(4,"40",first.operation().operationId()));
        var result=submit(refund(5,"40",second.operation().operationId()));
        assertThat(result.accounting().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(result.accounting().capturedAmount()).isEqualByComparingTo("100");
        assertThat(result.accounting().refundedAmount()).isEqualByComparingTo("100");
        assertThat(result.accounting().feeAmount()).isEqualByComparingTo("5");
        assertThat(result.accounting().merchantPayable()).isEqualByComparingTo("-5");
        assertThat(count("ledger_transactions")).isEqualTo(7); assertThat(count("ledger_entries")).isEqualTo(14);
        assertThat(count("payment_outbox")).isEqualTo(7); assertThat(count("merchant_webhook_deliveries")).isEqualTo(5);
        var balances=ledger.merchantBalances(A,"INR");
        assertThat(balances.stream().filter(b->b.account()==LedgerAccount.PROCESSOR_CLEARING).findFirst().orElseThrow().balance()).isEqualByComparingTo("0");
        assertThat(balances.stream().filter(b->b.account()==LedgerAccount.PLATFORM_FEE_REVENUE).findFirst().orElseThrow().balance()).isEqualByComparingTo("5");
    }
    @Test void overCaptureRollsBackWithoutAnyFinancialOrEventEffect() {
        submit(capture(1,"60","1")); refused(capture(2,"41","0"));
        assertThat(view().capturedAmount()).isEqualByComparingTo("60"); assertThat(count("payment_amount_operations")).isEqualTo(1);
        assertThat(count("ledger_transactions")).isEqualTo(2); assertThat(count("payment_outbox")).isEqualTo(3);
    }
    @Test void refundIsBoundToItsCaptureRatherThanOtherCapturedCapacity() {
        var first=submit(capture(1,"40","0")); submit(capture(2,"60","0"));
        refused(refund(3,"41",first.operation().operationId())); assertThat(view().refundedAmount()).isEqualByComparingTo("0");
    }
    @Test void firstRefundClosesUnusedCaptureAndFullPrincipalRefundIsTerminal() {
        var first=submit(capture(1,"50","1")); submit(refund(2,"10",first.operation().operationId()));
        assertThat(view().remainingCaptureAmount()).isEqualByComparingTo("0"); refused(capture(3,"1","0"));
        submit(refund(3,"40",first.operation().operationId())); assertThat(view().status()).isEqualTo(PaymentStatus.REFUNDED);
        refused(refund(4,"1",first.operation().operationId()));
    }
    @Test void exactAcceptedReplayRetainsItsHistoricalSnapshotAfterLaterWork() {
        var command=capture(1,"40","2"); var first=submit(command); submit(capture(2,"60","1"));
        var replay=submit(new Command(command.requestId(),1,Action.CAPTURE,new BigDecimal("40.000"),new BigDecimal("2.0"),null));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.operation()).isEqualTo(first.operation()); assertThat(replay.accounting()).isEqualTo(first.accounting());
        assertThat(view().capturedAmount()).isEqualByComparingTo("100");
    }
    @Test void changedRequestReuseAndStaleVersionAreConflicts() {
        var command=capture(1,"40","2"); submit(command);
        refused(new Command(command.requestId(),1,Action.CAPTURE,new BigDecimal("40"),BigDecimal.ONE,null));
        refused(capture(1,"10","0")); assertThat(count("payment_amount_operations")).isEqualTo(1);
    }
    @Test void manualFullEndpointsCannotMixWithAmountMode() {
        submit(capture(1,"40","1"));
        assertThatThrownBy(()->payments.capturePayment(payment)).isInstanceOf(PaymentAccountingException.class);
        assertThatThrownBy(()->payments.failPayment(payment)).isInstanceOf(PaymentAccountingException.class);
        assertThatThrownBy(()->payments.refundPayment(payment)).isInstanceOf(PaymentAccountingException.class);
    }
    @Test void fullProviderManagedCommandCannotBeLocallyPartiallyCaptured() {
        var id=payments.createPayment(A,"provider-bound-"+UUID.randomUUID(),new CreatePaymentRequest("P",new BigDecimal("10"),"INR")).payment().id();
        provider.prepare(A,id,new ProviderOperation.Command(UUID.randomUUID(),ProviderOperation.Action.AUTHORIZE,ProviderOperation.Scenario.APPROVE));
        assertThatThrownBy(()->as(A,()->accounting.submit(id,capture(0,"1","0")))).isInstanceOf(PaymentAccountingException.class);
        assertThat(count("payment_amount_operations")).isZero();
    }
    @Test void actualMerchantApiPreservesOwnershipAuthenticationAndCsrf() throws Exception {
        String path="/api/v1/payments/"+payment+"/amount-operations";
        mvc.perform(get("/api/v1/payments/"+payment+"/accounting")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments/"+payment+"/accounting").with(httpBasic("accounting-merchant-b",PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(httpBasic(USER,PASSWORD)).contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(capture(1,"10","1")))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(httpBasic("accounting-merchant-b",PASSWORD)).with(csrf()).contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(capture(1,"10","1")))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(httpBasic(USER,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON).content("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"CAPTURE\",\"amount\":10,\"fee\":1}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(httpBasic(USER,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(capture(1,"10","1")))).andExpect(status().isOk());
    }
    @Test void concurrentCaptureCannotOverspendAuthorization() throws Exception { race(capture(1,"70","0"),capture(1,"70","0")); assertThat(view().capturedAmount()).isEqualByComparingTo("70"); }
    @Test void concurrentRefundCannotOverspendCapture() throws Exception {
        var capture=submit(capture(1,"100","0")); race(refund(2,"70",capture.operation().operationId()),refund(2,"70",capture.operation().operationId()));
        assertThat(view().refundedAmount()).isEqualByComparingTo("70");
    }
    void race(Command a,Command b) throws Exception {
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>(); for(var command:List.of(a,b)) results.add(pool.submit(()->{
                start.await(); try {submit(command); return true;} catch(RuntimeException failure) {
                    if(failure instanceof PaymentAccountingException refused && refused.status()==409) return false;
                    for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                        if(cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) return false;
                    throw failure;
                }
            }));
            start.countDown(); int successes=0; for(var future:results) if(future.get(20,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void outboxFailureRollsBackCommandBindingPaymentAndEveryJournal() {
        var target=AopTestUtils.<OutboxService>getUltimateTargetObject(outbox); doThrow(new IllegalStateException("injected outbox failure")).when(target).record(any(Payment.class));
        var command=capture(1,"50","2"); assertThatThrownBy(()->submit(command)).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_amount_bindings")).isZero(); assertThat(count("payment_amount_operations")).isZero(); assertThat(count("ledger_transactions")).isZero();
        assertThat(view().version()).isEqualTo(1); reset(target); assertThat(submit(command).replayed()).isFalse();
    }
    @Test void deferredMissingFeeJournalRollsBackEverything() {
        var target=AopTestUtils.<LedgerRepository>getUltimateTargetObject(ledger);
        doAnswer(call->{LedgerTransaction t=call.getArgument(0); return t.operation()==LedgerOperation.FEE ? null : call.callRealMethod();}).when(target).save(any());
        assertThatThrownBy(()->submit(capture(1,"50","2"))).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_amount_operations")).isZero(); assertThat(count("ledger_transactions")).isZero(); assertThat(view().version()).isEqualTo(1);
    }
    @Test void acceptedOperationAndAmountPaymentGuardsRejectRawMutation() {
        var op=submit(capture(1,"50","1")).operation();
        assertThatThrownBy(()->jdbc.update("UPDATE payment_amount_operations SET fee=0 WHERE operation_id=?",op.operationId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM payment_amount_operations WHERE operation_id=?",op.operationId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE payments SET status='REFUNDED',version=version+1 WHERE id=?",payment)).isInstanceOf(RuntimeException.class);
    }
    @Test void legacyFullLifecycleRetainsTwoJournalsAndNoFee() {
        payments.capturePayment(payment); payments.refundPayment(payment);
        assertThat(count("ledger_transactions")).isEqualTo(2); assertThat(count("ledger_accounts")).isEqualTo(2);
        assertThat(view().amountManaged()).isFalse(); assertThat(view().refundedAmount()).isEqualByComparingTo("100"); assertThat(view().feeAmount()).isEqualByComparingTo("0");
    }
    @Test void partialEventsReachConsumerAndLateSnapshotCannotRewindProjection() throws Exception {
        submit(capture(1,"40","1")); submit(capture(2,"20","1"));
        var events=jdbc.query("SELECT payload FROM payment_outbox WHERE payment_id=? ORDER BY payment_version DESC",(r,n)->r.getString(1),payment);
        for(var json:events) consumer.consume(mapper.readValue(json,PaymentEvent.class));
        var projection=consumer.getProjection(payment); assertThat(projection.status()).isEqualTo(PaymentStatus.PARTIAL_CAPTURE);
        assertThat(projection.paymentVersion()).isEqualTo(3);
    }
    @Test void feeCanEqualCaptureAndRemainsAfterPrincipalRefund() {
        var c=submit(capture(1,"10","10")); assertThat(c.accounting().merchantPayable()).isEqualByComparingTo("0");
        var r=submit(refund(2,"10",c.operation().operationId())); assertThat(r.accounting().merchantPayable()).isEqualByComparingTo("-10");
    }
    @Test void lifetimeCommandCapIsEnforcedWithoutPartialCommit() {
        for(int i=0;i<100;i++) submit(capture(i+1,"0.01","0"));
        refused(capture(101,"0.01","0")); assertThat(view().capturedAmount()).isEqualByComparingTo("1"); assertThat(count("payment_amount_operations")).isEqualTo(100);
    }
    @Test void v19UpgradePreservesExistingRowsAndStartsWithNoAmountBindings() throws Exception {
        String schema="m20_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var flyway=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("19").load(); flyway.migrate();
        String before;
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setSchema(schema); var statement=connection.createStatement();
            statement.execute("INSERT INTO payments(id,merchant_reference,amount,currency,status,created_at,updated_at,version) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa20','UPGRADE',10,'INR','AUTHORIZED',now(),now(),1)");
            var row=statement.executeQuery("SELECT row_to_json(p)::text FROM payments p"); row.next(); before=row.getString(1);
        }
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).load().migrate();
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setSchema(schema); var statement=connection.createStatement(); var row=statement.executeQuery("SELECT row_to_json(p)::text FROM payments p"); row.next(); assertThat(row.getString(1)).isEqualTo(before);
            var count=statement.executeQuery("SELECT count(*) FROM payment_amount_bindings"); count.next(); assertThat(count.getInt(1)).isZero();
        }
    }
}
