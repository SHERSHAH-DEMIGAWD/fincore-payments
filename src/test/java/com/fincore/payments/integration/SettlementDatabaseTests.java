package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.Settlement.*;
import com.fincore.payments.dto.ProviderOperation.Action;
import com.fincore.payments.dto.ProviderOperation.Scenario;
import com.fincore.payments.exception.SettlementException;
import com.fincore.payments.provider.SandboxProviderServer;
import com.fincore.payments.repository.SettlementRepository;
import com.fincore.payments.service.*;
import com.fincore.payments.settlement.*;
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
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false","fincore.settlement.enabled=true"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class SettlementDatabaseTests {
    static final UUID INSTANCE=UUID.randomUUID();
    static final String API="test-only-provider-secret-0123456789",KEY="test-only-settlement-secret-0123456789",PASSWORD="settlement-test-password";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD),ROOT="/api/v1/merchant/settlement-statements";
    static SandboxProviderServer sandbox;
    static synchronized SandboxProviderServer sandbox(){if(sandbox==null)try{
        sandbox=new SandboxProviderServer(0,Files.createTempDirectory("fincore-settlement-it-"),INSTANCE,API,null,"sandbox-v1",KEY);
    }catch(Exception failed){throw new IllegalStateException(failed);}return sandbox;}
    @DynamicPropertySource static void provider(DynamicPropertyRegistry r){
        r.add("fincore.provider.enabled",()->true);r.add("fincore.provider.base-url",()->"http://127.0.0.1:"+sandbox().port());
        r.add("fincore.provider.api-key",()->API);r.add("fincore.provider.instance-id",()->INSTANCE);
        r.add("fincore.provider.timeout-millis",()->2000);r.add("fincore.provider.lease-seconds",()->5);
        r.add("fincore.settlement.signing-key",()->KEY);r.add("fincore.settlement.instance-id",()->INSTANCE);
    }
    @AfterAll static void stop()throws Exception{if(sandbox!=null){sandbox.close();sandbox=null;}}
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired ProviderOperationService provider;
    @Autowired SettlementService settlements;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean SettlementRepository repository;
    UUID a,b,payment;String user,otherUser;
    @BeforeEach void setup(){
        jdbc.execute("TRUNCATE settlement_resolutions,settlement_reports,settlement_statements");
        a=UUID.randomUUID();b=UUID.randomUUID();user="settlement-a-"+a;otherUser="settlement-b-"+b;
        for(var entry:List.of(Map.entry(a,user),Map.entry(b,otherUser)))jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true)",entry.getKey(),entry.getValue(),HASH);
        payment=payments.createPayment(a,"settle-"+UUID.randomUUID(),new CreatePaymentRequest("SETTLEMENT-ORDER",new BigDecimal("100"),"INR")).payment().id();
        submit(new ProviderOperation.Command(UUID.randomUUID(),Action.AUTHORIZE,Scenario.APPROVE));
    }
    @AfterEach void resetSpy(){reset((Object)AopTestUtils.getUltimateTargetObject(repository));SecurityContextHolder.clearContext();}
    <T>T as(UUID merchant,Supplier<T> work){var before=SecurityContextHolder.getContext();var c=SecurityContextHolder.createEmptyContext();
        var principal=new MerchantPrincipal(merchant,merchant.equals(a)?user:otherUser,HASH,true);
        c.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",principal.getAuthorities()));SecurityContextHolder.setContext(c);
        try{return work.get();}finally{SecurityContextHolder.setContext(before);}}
    ProviderOperation.Result submit(ProviderOperation.Command cmd){return as(a,()->provider.submit(payment,cmd));}
    ProviderOperation.Command capture(Scenario scenario){return new ProviderOperation.Command(UUID.randomUUID(),Action.CAPTURE,scenario);}
    byte[] exported()throws Exception{try(var http=HttpClient.newHttpClient()){
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+sandbox().port()+"/v1/settlements/"+a+"/INR"))
            .header("Authorization","Bearer "+API).timeout(java.time.Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(SettlementWire.authentic(response.body(),"sandbox-v1",response.headers().firstValue("Fincore-Settlement-Signature").orElseThrow(),KEY)).isTrue();return response.body();}}
    ImportResult imported(byte[] bytes)throws Exception{return mapper.readValue(mvc.perform(post(ROOT).with(httpBasic(user,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
        .header("Fincore-Settlement-Key-Id","sandbox-v1").header("Fincore-Settlement-Signature",SettlementWire.signature(bytes,"sandbox-v1",KEY)).content(bytes))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),ImportResult.class);}
    Report report(UUID statement){return as(a,()->settlements.reconcile(statement,new Reconcile(UUID.randomUUID()))).report();}
    long count(String table){return Set.of("ledger_transactions","payment_outbox").contains(table)
        ?jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE payment_id=?",Long.class,payment)
        :jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void conflict(Runnable work){assertThatThrownBy(work::run).isInstanceOfSatisfying(SettlementException.class,e->assertThat(e.status()).isEqualTo(409));}

    @Test void independentlyExportedLifecycleMatchesAndNeverPostsAnotherFinancialEffect()throws Exception{
        submit(capture(Scenario.APPROVE));byte[] bytes=exported();long journals=count("ledger_transactions"),events=count("payment_outbox");
        var imported=imported(bytes);var result=report(imported.statement().statementId());
        assertThat(result.comparison().matched()).isTrue();assertThat(result.comparison().external().net()).isEqualByComparingTo("100");
        assertThat(result.comparison().local().net()).isEqualByComparingTo("100");
        assertThat(imported(bytes).replayed()).isTrue();assertThat(count("ledger_transactions")).isEqualTo(journals);assertThat(count("payment_outbox")).isEqualTo(events);
        mvc.perform(get(ROOT+"/"+imported.statement().statementId()+"/document").with(httpBasic(user,PASSWORD)))
            .andExpect(status().isOk()).andExpect(header().string("X-Content-SHA256",SettlementWire.sha256(bytes))).andExpect(content().bytes(bytes));
        assertThat(as(a,()->settlements.reports(imported.statement().statementId(),null,10)).reports()).hasSize(1);
    }
    @Test void unknownCaptureIsExposedThenRecoveryPermitsAnAttributedResolution()throws Exception{
        var intent=capture(Scenario.TIMEOUT_AFTER_ACCEPT);assertThat(submit(intent).operation().state()).isEqualTo(ProviderOperation.State.UNKNOWN);
        var statement=imported(exported()).statement().statementId();var old=report(statement);
        assertThat(old.comparison().matched()).isFalse();assertThat(old.comparison().items().getFirst().issues()).contains(Issue.LOCAL_UNCONFIRMED);
        assertThat(count("ledger_transactions")).isZero();
        conflict(()->as(a,()->settlements.resolve(statement,old.reportId(),new Resolve(UUID.randomUUID(),old.reportId(),"Cannot override evidence"))));
        assertThat(submit(intent).operation().state()).isEqualTo(ProviderOperation.State.APPLIED);var matched=report(statement);
        assertThat(matched.comparison().matched()).isTrue();var command=new Resolve(UUID.randomUUID(),matched.reportId(),"Original provider command recovered");
        var resolution=as(a,()->settlements.resolve(statement,old.reportId(),command));
        assertThat(resolution.replayed()).isFalse();assertThat(resolution.resolution().actor()).isEqualTo(user);
        assertThat(as(a,()->settlements.resolve(statement,old.reportId(),command)).replayed()).isTrue();
        assertThat(as(a,()->settlements.report(statement,old.reportId())).comparison().matched()).isFalse();assertThat(count("ledger_transactions")).isEqualTo(1);
        conflict(()->as(a,()->settlements.resolve(statement,old.reportId(),new Resolve(command.requestId(),matched.reportId(),"Changed reason"))));
    }
    @Test void oldCumulativeStatementDetectsLaterCaptureAndRefundRatherThanClaimingCoverage()throws Exception{
        var empty=imported(exported()).statement().statementId();submit(capture(Scenario.APPROVE));
        assertThat(report(empty).comparison().items().getFirst().issues()).contains(Issue.MISSING_EXTERNAL);
        var captured=imported(exported()).statement().statementId();assertThat(report(captured).comparison().matched()).isTrue();
        submit(new ProviderOperation.Command(UUID.randomUUID(),Action.REFUND,Scenario.APPROVE));
        assertThat(report(captured).comparison().matched()).isFalse();var refunded=imported(exported()).statement().statementId();
        assertThat(report(refunded).comparison().local().net()).isZero();assertThat(report(refunded).comparison().matched()).isTrue();
    }
    @Test void amountAndFeeDifferencesRemainUnresolvedDespiteAValidSignature()throws Exception{
        submit(capture(Scenario.APPROVE));var original=SettlementWire.read(exported());var line=original.lines().getFirst();
        var changed=new Line(line.sequence(),line.operationId(),line.paymentId(),line.action(),new BigDecimal("99"),BigDecimal.ONE,line.providerPaymentId(),line.providerOperationId());
        var s=new Statement(1,original.statementId(),INSTANCE,a,"INR",original.throughSequence(),List.of(changed),new BigDecimal("99"),BigDecimal.ZERO,BigDecimal.ONE,new BigDecimal("98"));
        var statement=imported(SettlementWire.encode(s)).statement().statementId();var report=report(statement);
        assertThat(report.comparison().items().getFirst().issues()).contains(Issue.AMOUNT_MISMATCH,Issue.FEE_MISMATCH);
        conflict(()->as(a,()->settlements.resolve(statement,report.reportId(),new Resolve(UUID.randomUUID(),report.reportId(),"No financial override"))));
    }
    @Test void changedBytesUnderTheSameStatementIdentityConflict()throws Exception{
        var bytes=exported();var statement=imported(bytes).statement().statementId();
        var pretty=(new String(bytes,StandardCharsets.UTF_8)+"\n").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(ROOT).with(httpBasic(user,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
            .header("Fincore-Settlement-Key-Id","sandbox-v1").header("Fincore-Settlement-Signature",SettlementWire.signature(pretty,"sandbox-v1",KEY)).content(pretty)).andExpect(status().isConflict());
        assertThat(as(a,()->settlements.get(statement)).sha256()).isEqualTo(SettlementWire.sha256(bytes));
    }
    @Test void genuineBasicCsrfAndOwnershipProtectEveryEvidenceReadAndWrite()throws Exception{
        var bytes=exported();var statement=imported(bytes).statement().statementId();var report=report(statement);
        mvc.perform(get(ROOT).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT).with(httpBasic(user,PASSWORD)).contentType(APPLICATION_JSON).content(bytes)).andExpect(status().isForbidden());
        mvc.perform(get(ROOT+"/"+statement).with(httpBasic(otherUser,PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(ROOT+"/"+statement+"/reports/"+report.reportId()).with(httpBasic(otherUser,PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(ROOT+"/"+statement+"/document").with(httpBasic(otherUser,PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post(ROOT).with(httpBasic(otherUser,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
            .header("Fincore-Settlement-Key-Id","sandbox-v1").header("Fincore-Settlement-Signature",SettlementWire.signature(bytes,"sandbox-v1",KEY)).content(bytes)).andExpect(status().isNotFound());
        assertThat(as(b,()->settlements.list(null,50)).statements()).isEmpty();
    }
    @Test void invalidMacDuplicateHeadersAndOversizedBodiesProduceNoEvidence()throws Exception{
        byte[] bytes=exported();
        mvc.perform(post(ROOT).with(httpBasic(user,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
            .header("Fincore-Settlement-Key-Id","sandbox-v1").header("Fincore-Settlement-Signature","v1="+"0".repeat(64)).content(bytes)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT).with(httpBasic(user,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
            .header("Fincore-Settlement-Key-Id","sandbox-v1","sandbox-v1").header("Fincore-Settlement-Signature",SettlementWire.signature(bytes,"sandbox-v1",KEY)).content(bytes)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT).with(httpBasic(user,PASSWORD)).with(csrf()).contentType(APPLICATION_JSON)
            .header("Fincore-Settlement-Key-Id","sandbox-v1").header("Fincore-Settlement-Signature","v1="+"0".repeat(64)).content(new byte[SettlementWire.MAX_BYTES+1])).andExpect(status().isPayloadTooLarge());
        assertThat(count("settlement_statements")).isZero();
    }
    @Test void acceptedReportsReplayHistoricallyAndChangedStatementIntentConflicts()throws Exception{
        var first=imported(exported()).statement().statementId();var command=new Reconcile(UUID.randomUUID());
        var original=as(a,()->settlements.reconcile(first,command));submit(capture(Scenario.APPROVE));
        assertThat(as(a,()->settlements.reconcile(first,command)).report()).isEqualTo(original.report());
        var second=imported(exported()).statement().statementId();conflict(()->as(a,()->settlements.reconcile(second,command)));
        assertThat(report(first).comparison().matched()).isFalse();
    }
    @Test void aMatchingReportCannotCloseAChangedSourceSnapshot()throws Exception{
        var intent=capture(Scenario.TIMEOUT_AFTER_ACCEPT);submit(intent);var statement=imported(exported()).statement().statementId();var old=report(statement);
        submit(intent);var matching=report(statement);submit(new ProviderOperation.Command(UUID.randomUUID(),Action.REFUND,Scenario.APPROVE));
        conflict(()->as(a,()->settlements.resolve(statement,old.reportId(),new Resolve(UUID.randomUUID(),matching.reportId(),"Stale observation"))));
        assertThat(count("settlement_resolutions")).isZero();
    }
    @Test void lateReportFailureRollsBackEvidenceAndLeavesFinancialRowsAlone()throws Exception{
        submit(capture(Scenario.APPROVE));var statement=imported(exported()).statement().statementId();long journals=count("ledger_transactions"),events=count("payment_outbox");
        doAnswer(inv->{inv.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("Injected late report failure");}).when(repository).insertReport(eq(a),any());
        assertThatThrownBy(()->report(statement)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class).hasMessageContaining("Injected late report failure");
        assertThat(count("settlement_reports")).isZero();assertThat(count("ledger_transactions")).isEqualTo(journals);assertThat(count("payment_outbox")).isEqualTo(events);
    }
    @Test void repeatableReadReportDoesNotMixConcurrentProviderRecovery()throws Exception{
        var intent=capture(Scenario.TIMEOUT_AFTER_ACCEPT);submit(intent);var statement=imported(exported()).statement().statementId();
        var reached=new CountDownLatch(1);var proceed=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(inv->{assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");reached.countDown();
            assertThat(proceed.await(10,TimeUnit.SECONDS)).isTrue();return inv.callRealMethod();}).when(repository).local(eq(a),eq(INSTANCE),eq("INR"));
        try{var pending=pool.submit(()->report(statement));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();submit(intent);proceed.countDown();
            assertThat(pending.get(10,TimeUnit.SECONDS).comparison().matched()).isFalse();
        }finally{proceed.countDown();pool.shutdownNow();reset((Object)AopTestUtils.getUltimateTargetObject(repository));}
        assertThat(report(statement).comparison().matched()).isTrue();
    }
    @Test void lateResolutionFailureRollsBackClosureAndKeepsBothReports()throws Exception{
        var intent=capture(Scenario.TIMEOUT_AFTER_ACCEPT);submit(intent);
        var statement=imported(exported()).statement().statementId();var old=report(statement);
        submit(intent);var matched=report(statement);long journals=count("ledger_transactions"),events=count("payment_outbox");
        var command=new Resolve(UUID.randomUUID(),matched.reportId(),"Original command recovered");
        doAnswer(inv->{inv.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("Injected late resolution failure");})
            .when(repository).insertResolution(eq(a),any());
        assertThatThrownBy(()->as(a,()->settlements.resolve(statement,old.reportId(),command)))
            .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class).hasMessageContaining("Injected late resolution failure");
        assertThat(count("settlement_resolutions")).isZero();assertThat(count("settlement_reports")).isEqualTo(2);
        assertThat(count("ledger_transactions")).isEqualTo(journals);assertThat(count("payment_outbox")).isEqualTo(events);
        reset((Object)AopTestUtils.getUltimateTargetObject(repository));
        assertThat(as(a,()->settlements.resolve(statement,old.reportId(),command)).replayed()).isFalse();
    }
    @Test void databaseRejectsReportThatHidesAnIssueBehindMatchingTotals()throws Exception{
        var statement=imported(exported()).statement().statementId();var original=report(statement);
        var falseItem=new Item(UUID.randomUUID(),payment,Action.CAPTURE,null,null,null,null,null,List.of(Issue.UNMATCHED_EXTERNAL));
        var inconsistent=new Report(UUID.randomUUID(),statement,original.observedAt(),original.localFingerprint(),
            new Comparison(true,0,original.comparison().external(),original.comparison().local(),List.of(falseItem)));
        assertThatThrownBy(()->repository.insertReport(a,inconsistent)).isInstanceOf(org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("Settlement report issue count differs");
        assertThat(count("settlement_reports")).isEqualTo(1);
    }
    @Test void comparisonBudgetRefusesNewWorkButAcceptedRequestStillReplays()throws Exception{
        submit(capture(Scenario.APPROVE));var statement=imported(exported()).statement().statementId();var request=new Reconcile(UUID.randomUUID());
        var accepted=as(a,()->settlements.reconcile(statement,request));
        var row=repository.local(a,INSTANCE,"INR").getFirst();
        doReturn(Collections.nCopies(1001,row)).when(repository).local(eq(a),eq(INSTANCE),eq("INR"));
        assertThat(as(a,()->settlements.reconcile(statement,request)).report()).isEqualTo(accepted.report());
        conflict(()->report(statement));assertThat(count("settlement_reports")).isEqualTo(1);
    }
    @Test void merchantEvidenceFenceRefusesContentionWithoutASecondDecision()throws Exception{
        var statement=imported(exported()).statement().statementId();var pool=Executors.newSingleThreadExecutor();
        try{new TransactionTemplate(manager).execute(tx->{jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,21))",Object.class,a.toString());
            try{assertThat(pool.submit(()->{try{report(statement);return false;}catch(SettlementException e){return e.status()==409;}}).get(5,TimeUnit.SECONDS)).isTrue();}
            catch(Exception failed){throw new AssertionError(failed);}return null;});
        }finally{pool.shutdownNow();}assertThat(count("settlement_reports")).isZero();assertThat(report(statement).comparison().matched()).isTrue();
    }
    @Test void evidenceIsImmutableAndDatabaseRejectsInventedResolution()throws Exception{
        var statement=imported(exported()).statement().statementId();var matched=report(statement);
        for(String sql:List.of("UPDATE settlement_statements SET key_id='changed'","DELETE FROM settlement_statements","UPDATE settlement_reports SET matched=false","DELETE FROM settlement_reports"))
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO settlement_resolutions(merchant_id,request_id,statement_id,discrepant_report_id,matched_report_id,actor,reason,local_fingerprint) VALUES(?,?,?,?,?,?,?,?)",
            a,UUID.randomUUID(),statement,matched.reportId(),matched.reportId(),user,"Invalid",matched.localFingerprint())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void paginationAndLimitsAreOwnedAndBounded()throws Exception{
        var first=imported(exported()).statement().statementId();submit(capture(Scenario.APPROVE));var second=imported(exported()).statement().statementId();
        var page=as(a,()->settlements.list(null,1));assertThat(page.statements()).hasSize(1);assertThat(page.nextAfterStatementId()).isNotNull();
        assertThat(as(a,()->settlements.list(page.nextAfterStatementId(),1)).statements()).hasSize(1);
        mvc.perform(get(ROOT).param("limit","101").with(httpBasic(user,PASSWORD))).andExpect(status().isBadRequest());
        assertThat(as(b,()->settlements.list(null,1)).statements()).isEmpty();
        var r1=report(first);var r2=report(first);assertThat(as(a,()->settlements.reports(first,null,1)).nextAfterReportId()).isNotNull();
    }
    @Test void declinesDoNotBorrowALaterApprovedCommandsJournal()throws Exception{
        var declined=submit(capture(Scenario.DECLINE)).operation();submit(capture(Scenario.APPROVE));
        var statement=imported(exported()).statement().statementId();var report=report(statement);
        assertThat(report.comparison().matched()).isTrue();assertThat(report.comparison().items().stream().filter(i->i.operationId().equals(declined.operationId())).findFirst().orElseThrow().journalId()).isNull();
        assertThat(report.comparison().local().captured()).isEqualByComparingTo("100");
    }
    @Test void v20UpgradePreservesExistingPaymentBeforeImage()throws Exception{
        String schema="m21_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("20").load().migrate();String before;
        try(var connection=jdbc.getDataSource().getConnection()){connection.setSchema(schema);var sql=connection.createStatement();
            sql.execute("INSERT INTO payments(id,merchant_reference,amount,currency,status,created_at,updated_at,version) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa21','UPGRADE',10,'INR','AUTHORIZED',now(),now(),1)");
            var rows=sql.executeQuery("SELECT row_to_json(p)::text FROM payments p");rows.next();before=rows.getString(1);}
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).load().migrate();
        try(var connection=jdbc.getDataSource().getConnection()){connection.setSchema(schema);var sql=connection.createStatement();var rows=sql.executeQuery("SELECT row_to_json(p)::text FROM payments p");rows.next();assertThat(rows.getString(1)).isEqualTo(before);
            var count=sql.executeQuery("SELECT count(*) FROM settlement_statements");count.next();assertThat(count.getInt(1)).isZero();}
    }
}
