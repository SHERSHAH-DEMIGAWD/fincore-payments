package com.fincore.payments.integration;

import com.fincore.payments.config.MerchantPrincipal;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.PaymentRisk.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.repository.PaymentRiskRepository;
import com.fincore.payments.risk.RiskVelocity;
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
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
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

/** Real PostgreSQL/credentials; Redis gateway is deliberately mocked in this suite. */
@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false","fincore.risk.enabled=true",
    "fincore.risk.maximum-amount=100.00","fincore.risk.max-admissions=2","fincore.risk.window-seconds=1","fincore.risk.namespace=m22-test"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PaymentRiskDatabaseTests {
    static final String PASSWORD="risk-test-password",HASH=new BCryptPasswordEncoder(4).encode(PASSWORD),ROOT="/api/v1/merchant/risk-decisions";
    @Autowired JdbcTemplate jdbc;@Autowired MerchantPaymentService merchantPayments;@Autowired PaymentService trustedPayments;
    @Autowired PaymentRiskService risk;@Autowired PlatformTransactionManager manager;@Autowired MockMvc mvc;
    @MockitoBean RiskVelocity velocity;@MockitoSpyBean PaymentRiskRepository repository;@MockitoSpyBean OutboxService outbox;
    UUID a,b;String user,other;
    @BeforeEach void setup(){
        jdbc.execute("TRUNCATE payment_risk_decisions,payment_risk_intents");a=UUID.randomUUID();b=UUID.randomUUID();user="risk-a-"+a;other="risk-b-"+b;
        jdbc.update("INSERT INTO merchants(id,username,password_hash,enabled) VALUES(?,?,?,true),(?,?,?,true)",a,user,HASH,b,other,HASH);
        when(velocity.reserve(any(),any())).thenReturn(new RiskVelocity.Reservation(true,1,1000));
    }
    @AfterEach void cleanup(){reset((Object)AopTestUtils.getUltimateTargetObject(repository));reset((Object)AopTestUtils.getUltimateTargetObject(outbox));SecurityContextHolder.clearContext();}
    <T>T as(UUID merchant,Supplier<T> work){var before=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
        var principal=new MerchantPrincipal(merchant,merchant.equals(a)?user:other,HASH,true);context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",principal.getAuthorities()));
        SecurityContextHolder.setContext(context);try{return work.get();}finally{SecurityContextHolder.setContext(before);}}
    CreatePaymentRequest request(String amount,String currency){return new CreatePaymentRequest("RISK-ORDER",new BigDecimal(amount),currency);}
    CreatePaymentResult create(String key){return as(a,()->merchantPayments.createPayment(key,request("10","INR")));}
    long count(String table){return table.equals("payment_outbox")
        ?jdbc.queryForObject("SELECT count(*) FROM payment_outbox o JOIN payments p ON p.id=o.payment_id WHERE p.merchant_id=?",Long.class,a)
        :jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE merchant_id=?",Long.class,a);}
    PaymentRiskException check(int status,Runnable work){var e=catchThrowableOfType(work::run,PaymentRiskException.class);assertThat(e).isNotNull();assertThat(e.status()).isEqualTo(status);return e;}
    @Test void allowedCreationAndLostResponseRetryUseOneDecisionAndOnePayment(){
        var original=create("one");assertThat(original.created()).isTrue();reset(velocity);
        when(velocity.reserve(any(),any())).thenThrow(new IllegalStateException("offline"));
        assertThat(create("one").payment()).isEqualTo(original.payment());verifyNoInteractions(velocity);
        assertThat(count("payment_risk_decisions")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_outbox")).isEqualTo(1);
    }
    @Test void staticAmountAndCurrencyRulesPersistWithoutCallingRedis(){
        check(422,()->as(a,()->merchantPayments.createPayment("large",request("100.01","INR"))));
        check(422,()->as(a,()->merchantPayments.createPayment("currency",request("10","USD"))));verifyNoInteractions(velocity);
        assertThat(count("payments")).isZero();assertThat(count("payment_risk_decisions")).isEqualTo(2);
    }
    @Test void staticDenialIsHistoricalAndChangedIntentConflicts(){
        var first=check(422,()->as(a,()->merchantPayments.createPayment("denied",request("101","INR"))));
        assertThat(check(422,()->as(a,()->merchantPayments.createPayment("denied",request("101.00","INR")))).decisionId()).isEqualTo(first.decisionId());
        assertThatThrownBy(()->create("denied")).isInstanceOf(IdempotencyConflictException.class);assertThat(count("payment_risk_decisions")).isEqualTo(1);
    }
    @Test void throttleCanRecoverUsingTheSameBoundIntent(){
        when(velocity.reserve(any(),any())).thenReturn(new RiskVelocity.Reservation(false,2,1),new RiskVelocity.Reservation(true,1,1000));
        var rejected=check(429,()->create("throttled"));assertThat(rejected.retrySeconds()).isEqualTo(1);assertThat(count("payments")).isZero();
        assertThat(create("throttled").created()).isTrue();assertThat(count("payment_risk_intents")).isEqualTo(1);assertThat(count("payment_risk_decisions")).isEqualTo(2);
    }
    @Test void outageFailsClosedAndRetryCanRecoverWithoutChangingTheIntent(){
        when(velocity.reserve(any(),any())).thenThrow(new IllegalStateException("private-server-detail")).thenReturn(new RiskVelocity.Reservation(true,1,1000));
        check(503,()->create("outage"));assertThat(count("payments")).isZero();assertThat(count("idempotency_records")).isZero();assertThat(count("payment_outbox")).isZero();
        assertThat(create("outage").created()).isTrue();assertThat(count("payment_risk_decisions")).isEqualTo(2);
    }
    @Test void acceptedFinancialRetryFromBeforeRiskEnablementDoesNotRequireRedis(){
        var old=trustedPayments.createPayment(a,"old",request("1000","USD"));reset(velocity);
        assertThat(as(a,()->merchantPayments.createPayment("old",request("1000.00","USD"))).payment()).isEqualTo(old.payment());verifyNoInteractions(velocity);
        assertThat(count("payment_risk_decisions")).isZero();
    }
    @Test void financialRollbackPreservesAdmissionAndExactRetryDoesNotReserveAgain(){
        OutboxService target=AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("late source failure")).when(target).record(any());
        assertThatThrownBy(()->create("late-financial")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("payments")).isZero();assertThat(count("idempotency_records")).isZero();assertThat(count("payment_risk_decisions")).isEqualTo(1);
        reset((Object)AopTestUtils.getUltimateTargetObject(outbox));reset(velocity);
        assertThat(create("late-financial").created()).isTrue();verifyNoInteractions(velocity);
    }
    @Test void riskEvidenceFailureCreatesNoPaymentAndRetryUsesStableRedisIdentity(){
        var keys=new ArrayList<String>();when(velocity.reserve(any(),any())).thenAnswer(i->{keys.add(i.getArgument(1));return new RiskVelocity.Reservation(true,1,1000);});
        doAnswer(i->{i.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("late risk failure");}).when(repository).insertDecision(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->create("risk-rollback")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("payment_risk_intents")).isZero();assertThat(count("payment_risk_decisions")).isZero();assertThat(count("payments")).isZero();
        reset((Object)AopTestUtils.getUltimateTargetObject(repository));create("risk-rollback");assertThat(keys).hasSize(2);assertThat(keys.getFirst()).isEqualTo(keys.getLast());
    }
    @Test void actualBasicCsrfAndOwnershipProtectRiskEvidence()throws Exception{
        var decision=check(422,()->as(a,()->merchantPayments.createPayment("owned",request("101","INR"))));
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());mvc.perform(get(ROOT+"/"+decision.decisionId()).with(httpBasic(other,PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(ROOT).with(httpBasic(other,PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.decisions").isEmpty());
        mvc.perform(post("/api/v1/payments").with(httpBasic(user,PASSWORD)).header("Idempotency-Key","csrf").contentType(APPLICATION_JSON)
            .content("{\"merchantReference\":\"R\",\"amount\":10,\"currency\":\"INR\"}")).andExpect(status().isForbidden());
    }
    @Test void httpRefusalsExposeDecisionAndRetryHeadersWithoutPrivateFailureText()throws Exception{
        when(velocity.reserve(any(),any())).thenThrow(new IllegalStateException("private-server-detail"));
        mvc.perform(post("/api/v1/payments").with(httpBasic(user,PASSWORD)).with(csrf()).header("Idempotency-Key","api-outage").contentType(APPLICATION_JSON)
            .content("{\"merchantReference\":\"R\",\"amount\":10,\"currency\":\"INR\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Fincore-Risk-Decision-Id")).andExpect(header().string("Retry-After","1"))
            .andExpect(jsonPath("$.error").value("PAYMENT_RISK_UNAVAILABLE")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-server-detail"))));
        assertThat(count("payment_risk_decisions")).isEqualTo(1);assertThat(count("payments")).isZero();
    }
    @Test void independentMerchantKeysDoNotShareDecisions(){
        var p=create("same");var otherPayment=as(b,()->merchantPayments.createPayment("same",request("10","INR")));
        assertThat(otherPayment.payment().id()).isNotEqualTo(p.payment().id());verify(velocity).reserve(eq(a),any());verify(velocity).reserve(eq(b),any());
    }
    @Test void intentFenceRefusesContentionWithoutCallingRedis()throws Exception{
        var pool=Executors.newSingleThreadExecutor();try{new TransactionTemplate(manager).execute(tx->{repository.fence(a,"busy");
            try{assertThat(pool.submit(()->{try{create("busy");return 0;}catch(PaymentRiskException e){return e.status();}}).get(5,TimeUnit.SECONDS)).isEqualTo(409);}
            catch(Exception failed){throw new AssertionError(failed);}return null;});}finally{pool.shutdownNow();}verifyNoInteractions(velocity);assertThat(count("payments")).isZero();
    }
    @Test void redisRunsInSeparateEvidenceTransactionBeforeFinancialClaim(){
        when(velocity.reserve(any(),any())).thenAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(count("payments")).isZero();assertThat(count("idempotency_records")).isZero();return new RiskVelocity.Reservation(true,1,1000);});create("separate");
    }
    @Test void readPagesAndEvidenceRemainBoundedAndImmutable()throws Exception{
        create("page1");create("page2");var page=as(a,()->risk.page(null,1));assertThat(page.decisions()).hasSize(1);assertThat(page.nextAfterDecisionId()).isNotNull();
        assertThat(as(a,()->risk.page(page.nextAfterDecisionId(),1)).decisions()).hasSize(1);
        mvc.perform(get(ROOT).with(httpBasic(user,PASSWORD)).param("limit","101")).andExpect(status().isBadRequest());
        for(String table:List.of("payment_risk_decisions","payment_risk_intents")){
            assertThatThrownBy(()->jdbc.update("DELETE FROM "+table+" WHERE merchant_id=?",a)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE "+table+" SET id=id WHERE merchant_id=?",a)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    }
    @Test void databaseRejectsInventedPolicyAndSecondTerminalDecision(){
        create("guard");var intent=repository.intent(a,"guard").orElseThrow();var properties=new com.fincore.payments.config.RiskProperties(true,"INR",new BigDecimal("100"),2,1,"m22-test","127.0.0.1",6379,0,250,"","");
        assertThatThrownBy(()->repository.insertDecision(a,intent,Outcome.DENY,Reason.AMOUNT_LIMIT,properties,null)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->repository.insertDecision(a,intent,Outcome.ALLOW,Reason.VELOCITY_ALLOWED,properties,new RiskVelocity.Reservation(true,1,1000))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("payment_risk_decisions")).isEqualTo(1);
    }
    @Test void v21UpgradePreservesExistingPaymentBeforeImage()throws Exception{
        String schema="m22_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("21").load().migrate();String before;
        try(var c=jdbc.getDataSource().getConnection()){c.setSchema(schema);var s=c.createStatement();s.execute("INSERT INTO payments(id,merchant_reference,amount,currency,status,created_at,updated_at,version) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa22','UPGRADE',10,'INR','CREATED',now(),now(),0)");
            var r=s.executeQuery("SELECT row_to_json(p)::text FROM payments p");r.next();before=r.getString(1);}
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).load().migrate();
        try(var c=jdbc.getDataSource().getConnection()){c.setSchema(schema);var s=c.createStatement();var r=s.executeQuery("SELECT row_to_json(p)::text FROM payments p");r.next();assertThat(r.getString(1)).isEqualTo(before);
            var count=s.executeQuery("SELECT count(*) FROM payment_risk_decisions");count.next();assertThat(count.getInt(1)).isZero();}
    }
}
