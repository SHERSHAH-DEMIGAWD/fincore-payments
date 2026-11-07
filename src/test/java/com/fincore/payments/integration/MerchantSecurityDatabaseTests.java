package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.*;
import com.fincore.payments.dto.*;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real credentials, real CSRF, real PostgreSQL; no mocked authentication or filters disabled. */
@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
@AutoConfigureMockMvc
abstract class MerchantSecurityDatabaseTests {
    static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111116");
    static final UUID B=UUID.fromString("22222222-2222-4222-8222-222222222216");
    static final String PASSWORD_A="merchant-a-test-password",PASSWORD_B="merchant-b-test-password";
    static final String HASH_A=new BCryptPasswordEncoder(4).encode(PASSWORD_A);
    static final String HASH_B=new BCryptPasswordEncoder(4).encode(PASSWORD_B);
    static final String HASH_OPERATOR=new BCryptPasswordEncoder(4).encode("operator-only-password");
    @DynamicPropertySource static void operator(DynamicPropertyRegistry registry) {
        // Deliberately reuse the same username to prove providers do not fall back across realms.
        registry.add("fincore.operations.username",() -> "merchant-a");
        registry.add("fincore.operations.password-hash",() -> HASH_OPERATOR);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired PaymentEventConsumer consumer;
    @Autowired MerchantCredentialRepository credentials;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @MockitoSpyBean OutboxService outbox;
    private OutboxService outboxTarget;

    @BeforeEach void reset() {
        outboxTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
        jdbc.execute("""
            TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,
                projection_purge_plans,projection_routes,projection_route_audit,projection_rebuild_items,
                projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,consumer_event_failures,
                consumer_event_positions,outbox_requeue_audit,payment_event_projections,consumed_payment_events,
                local_payment_event_inbox,payment_outbox,ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments
            """);
        provision(A,"merchant-a",HASH_A); provision(B,"merchant-b",HASH_B);
    }

    @Test void missingUnknownWrongDisabledAndLegacyCredentialsAreRefusedWithoutWrites() throws Exception {
        mvc.perform(get("/api/v1/merchant/me")).andExpect(status().isUnauthorized());
        for (var pair:List.of(new String[]{"user","password"},new String[]{"merchant-a","wrong"},
                new String[]{"missing",PASSWORD_A},new String[]{"legacy-internal",PASSWORD_A}))
            mvc.perform(get("/api/v1/merchant/me").with(httpBasic(pair[0],pair[1])))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"));
        jdbc.update("UPDATE merchants SET enabled=false WHERE id=?",A);
        mvc.perform(get("/api/v1/merchant/me").with(httpBasic("merchant-a",PASSWORD_A))).andExpect(status().isUnauthorized());
        assertThat(count("payments")).isZero(); assertThat(count("idempotency_records")).isZero();
    }

    @Test void identityOnlyExposesAuthenticatedMerchantAndNoCredentialMaterial() throws Exception {
        var result=mvc.perform(get("/api/v1/merchant/me").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merchantId").value(A.toString()))
                .andExpect(jsonPath("$.username").value("merchant-a")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(HASH_A,PASSWORD_A,"password",B.toString());
    }

    @Test void actualCsrfTokenSessionAndBasicCredentialsAreAllRequiredForWrites() throws Exception {
        mvc.perform(post("/api/v1/payments").with(httpBasic("merchant-a",PASSWORD_A))
                .header("Idempotency-Key","csrf-key").contentType(APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        var token=mvc.perform(get("/api/v1/merchant/csrf").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isOk()).andReturn();
        var json=mapper.readTree(token.getResponse().getContentAsString());
        var session=(MockHttpSession) token.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(post("/api/v1/payments").session(session).with(httpBasic("merchant-a",PASSWORD_A))
                .header(json.path("headerName").asText(),json.path("token").asText())
                .header("Idempotency-Key","csrf-key").contentType(APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/merchant/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test void passwordRotationAndDisableTakeEffectOnTheNextRequestEvenWithAnOldCookie() throws Exception {
        var result=mvc.perform(get("/api/v1/merchant/csrf").with(httpBasic("merchant-a",PASSWORD_A))).andReturn();
        var session=(MockHttpSession) result.getRequest().getSession(false);
        jdbc.update("UPDATE merchants SET password_hash=? WHERE id=?",HASH_B,A);
        mvc.perform(get("/api/v1/merchant/me").session(session).with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/merchant/me").session(session).with(httpBasic("merchant-a",PASSWORD_B)))
                .andExpect(status().isOk());
        jdbc.update("UPDATE merchants SET enabled=false WHERE id=?",A);
        mvc.perform(get("/api/v1/merchant/me").session(session).with(httpBasic("merchant-a",PASSWORD_B)))
                .andExpect(status().isUnauthorized());
    }

    @Test void merchantsAndOperatorsCannotBorrowEachOthersAuthenticationProvider() throws Exception {
        mvc.perform(get("/api/v1/operations/projection-routing").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/merchant/me").with(httpBasic("merchant-a","operator-only-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/operations/projection-routing").with(httpBasic("merchant-a","operator-only-password")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/merchant/me").with(user("operator").roles("PROJECTION_OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/merchant/me").with(user("forged").roles("MERCHANT")))
                .andExpect(status().isForbidden());
    }

    @Test void identicalClientKeysAreIndependentAcrossMerchantsAndReplayOnlyWithinTheirScope() throws Exception {
        UUID first=create("merchant-a",PASSWORD_A,"same-key","10.00",201);
        UUID second=create("merchant-b",PASSWORD_B,"same-key","20.00",201);
        assertThat(first).isNotEqualTo(second);
        assertThat(create("merchant-a",PASSWORD_A,"same-key","10.00",200)).isEqualTo(first);
        create("merchant-a",PASSWORD_A,"same-key","11.00",409);
        assertThat(count("idempotency_records")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM payments WHERE id=?",UUID.class,first)).isEqualTo(A);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM payments WHERE id=?",UUID.class,second)).isEqualTo(B);
    }

    @Test void forgedBodyHeadersAndQueryParametersNeverChoosePaymentOwnership() throws Exception {
        var result=mvc.perform(post("/api/v1/payments").with(httpBasic("merchant-a",PASSWORD_A)).with(csrf())
                .header("Idempotency-Key","spoof").header("X-Merchant-Id",B.toString()).queryParam("merchantId",B.toString())
                .contentType(APPLICATION_JSON).content(body("10.00").replace("}",",\"merchantId\":\""+B+"\"}")))
                .andExpect(status().isCreated()).andReturn();
        UUID id=id(result);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM payments WHERE id=?",UUID.class,id)).isEqualTo(A);
        mvc.perform(get("/api/v1/payments/"+id).with(httpBasic("merchant-b",PASSWORD_B)).header("X-Merchant-Id",A.toString()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings={"","/ledger","/events","/projection"})
    void foreignAndMissingPaymentReadsShareTheSame404Contract(String suffix) throws Exception {
        UUID id=create("merchant-a",PASSWORD_A,"foreign-read","10.00",201);
        for (UUID candidate:List.of(id,UUID.randomUUID()))
            mvc.perform(get("/api/v1/payments/"+candidate+suffix).with(httpBasic("merchant-b",PASSWORD_B)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @ParameterizedTest @ValueSource(strings={"authorize","capture","fail","refund"})
    void foreignTransitionsDoNotChangeAnyApplicationState(String action) throws Exception {
        UUID id=create("merchant-a",PASSWORD_A,"foreign-write","10.00",201);
        var before=image();
        mvc.perform(post("/api/v1/payments/"+id+"/"+action).with(httpBasic("merchant-b",PASSWORD_B)).with(csrf()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        assertThat(image()).isEqualTo(before);
    }

    @Test void ownerCanCompleteLifecycleAndReadItsLedgerEventsAndProjection() throws Exception {
        UUID id=create("merchant-a",PASSWORD_A,"lifecycle","10.00",201);
        action(id,"authorize"); action(id,"capture");
        for (String payload:jdbc.queryForList("SELECT payload FROM payment_outbox WHERE payment_id=? ORDER BY payment_version",String.class,id))
            consumer.consume(mapper.readValue(payload,PaymentEvent.class));
        mvc.perform(get("/api/v1/payments/"+id+"/projection").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(get("/api/v1/payments/"+id+"/events").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/payments/"+id+"/ledger").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        action(id,"refund");
        var replay=mvc.perform(post("/api/v1/payments").with(httpBasic("merchant-a",PASSWORD_A)).with(csrf())
                .header("Idempotency-Key","lifecycle").contentType(APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED")).andReturn();
        assertThat(id(replay)).isEqualTo(id);
    }

    @Test void balancesIncludeOnlyOwnedJournalsAndDoNotRevealOtherMerchantsAccounts() throws Exception {
        UUID a=create("merchant-a",PASSWORD_A,"balance-a","10.00",201);
        assertThat(balances("merchant-b",PASSWORD_B)).isEmpty();
        action(a,"authorize"); action(a,"capture");
        assertThat(balances("merchant-b",PASSWORD_B)).isEmpty();
        UUID b=create("merchant-b",PASSWORD_B,"balance-b","999.00",201);
        payments.authorizePayment(b); payments.capturePayment(b);
        assertThat(balances("merchant-a",PASSWORD_A)).containsExactly(new BigDecimal("10.0"),new BigDecimal("10.0"));
        assertThat(balances("merchant-b",PASSWORD_B)).containsExactly(new BigDecimal("999.0"),new BigDecimal("999.0"));
        action(a,"refund");
        assertThat(balances("merchant-a",PASSWORD_A)).allMatch(v -> v.signum()==0);
        assertThat(balances("merchant-b",PASSWORD_B)).allMatch(v -> v.compareTo(new BigDecimal("999"))==0);
    }

    @Test void concurrentIdenticalRequestsCommitOnePaymentAndOneOutboxRecord() throws Exception {
        var barrier=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> request=() -> { barrier.await(10,TimeUnit.SECONDS); return createResult("merchant-a",PASSWORD_A,"race","10.00"); };
            var first=executor.submit(request); var second=executor.submit(request);
            var one=first.get(20,TimeUnit.SECONDS); var two=second.get(20,TimeUnit.SECONDS);
            assertThat(List.of(one.getResponse().getStatus(),two.getResponse().getStatus())).containsExactlyInAnyOrder(201,200);
            assertThat(id(one)).isEqualTo(id(two));
        }
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_outbox")).isEqualTo(1);
    }

    @Test void concurrentMerchantsCanEachClaimTheSameClientKey() throws Exception {
        var barrier=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return create("merchant-a",PASSWORD_A,"shared-race","10.00",201); });
            var two=executor.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return create("merchant-b",PASSWORD_B,"shared-race","20.00",201); });
            assertThat(one.get(20,TimeUnit.SECONDS)).isNotEqualTo(two.get(20,TimeUnit.SECONDS));
        }
        assertThat(count("payments")).isEqualTo(2); assertThat(count("idempotency_records")).isEqualTo(2);
    }

    @Test void lateOutboxFailureRollsBackMerchantPaymentAndClaimBeforeAnExactRetry() throws Exception {
        doThrow(new IllegalStateException("simulated merchant write failure")).when(outboxTarget).record(any());
        create("merchant-a",PASSWORD_A,"rollback","10.00",500);
        assertThat(count("payments")).isZero(); assertThat(count("idempotency_records")).isZero(); assertThat(count("payment_outbox")).isZero();
        doCallRealMethod().when(outboxTarget).record(any());
        create("merchant-a",PASSWORD_A,"rollback","10.00",201);
    }

    @Test void databaseRejectsOwnershipReassignmentAndCrossMerchantClaimsAtCommit() throws Exception {
        UUID payment=create("merchant-a",PASSWORD_A,"db-scope","10.00",201);
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET merchant_id=? WHERE id=?",B,payment))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var snapshot=jdbc.queryForMap("SELECT * FROM idempotency_records WHERE merchant_id=?",A);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.update("""
                INSERT INTO idempotency_records(merchant_id,idempotency_key,request_fingerprint,payment_id,response_body,created_at)
                VALUES (?,'cross-claim',?,?,?,clock_timestamp())
                """,B,snapshot.get("request_fingerprint"),payment,snapshot.get("response_body"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE idempotency_records SET merchant_id=? WHERE merchant_id=?",B,A))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM idempotency_records WHERE merchant_id=?",A))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void databaseProtectsCredentialIdentityLegacyScopeAndPasswordFormat() {
        for (String sql:List.of("UPDATE merchants SET username='renamed' WHERE id=?","DELETE FROM merchants WHERE id=?",
                "UPDATE merchants SET password_hash='plaintext' WHERE id=?"))
            assertThatThrownBy(() -> jdbc.update(sql,A)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE merchants SET enabled=true,password_hash=? WHERE id=?",HASH_A,MerchantIdentity.LEGACY_ID))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> credentials.load("legacy-internal"))
                .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
    }

    @Test void existingLegacyPaymentsAreNotExposedToNewMerchantAccounts() throws Exception {
        UUID id=payments.createPayment("legacy-key",new CreatePaymentRequest("LEGACY",BigDecimal.TEN,"INR")).payment().id();
        mvc.perform(get("/api/v1/payments/"+id).with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        assertThat(create("merchant-a",PASSWORD_A,"legacy-key","10.00",201)).isNotEqualTo(id);
        assertThat(count("idempotency_records")).isEqualTo(2);
    }

    @Test void ownedPaymentWithoutAProjectionReturnsTheProjectionSpecific404() throws Exception {
        UUID id=create("merchant-a",PASSWORD_A,"unprojected","10.00",201);
        mvc.perform(get("/api/v1/payments/"+id+"/projection").with(httpBasic("merchant-a",PASSWORD_A)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_PROJECTION_NOT_FOUND"));
    }

    @Test void v15UpgradePreservesOldRowsAndExactLegacyReplay() throws Exception {
        String schema="merchant_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try (var connection=dataSource.getConnection()) {
            String original=connection.getSchema();
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var sql=new JdbcTemplate(ds);
            sql.execute("CREATE SCHEMA \""+schema+"\"");
            try {
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("15").load().migrate();
                connection.setSchema(schema);
                var request=new CreatePaymentRequest("LEGACY-UPGRADE",BigDecimal.TEN,"INR");
                var payment=Payment.create(request.merchantReference(),request.amount(),request.currency());
                String snapshot=mapper.writeValueAsString(PaymentResponse.from(payment));
                sql.update("""
                        INSERT INTO payments(id,merchant_reference,amount,currency,status,created_at,updated_at,version)
                        VALUES (?,?,10,'INR','CREATED',?,?,0)
                        """,payment.getId(),request.merchantReference(),Timestamp.from(payment.getCreatedAt()),Timestamp.from(payment.getUpdatedAt()));
                sql.update("""
                        INSERT INTO idempotency_records(idempotency_key,request_fingerprint,payment_id,response_body,created_at)
                        VALUES ('legacy-upgrade',?,?,?,clock_timestamp())
                        """,PaymentRequestFingerprint.of(request),payment.getId(),snapshot);
                String beforePayment=sql.queryForObject("SELECT to_jsonb(p)::text FROM payments p",String.class);
                String beforeClaim=sql.queryForObject("SELECT to_jsonb(i)::text FROM idempotency_records i",String.class);
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                assertThat(sql.queryForObject("SELECT (to_jsonb(p)-'merchant_id')::text FROM payments p",String.class)).isEqualTo(beforePayment);
                assertThat(sql.queryForObject("SELECT (to_jsonb(i)-'merchant_id')::text FROM idempotency_records i",String.class)).isEqualTo(beforeClaim);
                assertThat(sql.queryForObject("SELECT merchant_id FROM payments",UUID.class)).isEqualTo(MerchantIdentity.LEGACY_ID);
                var repository=mock(PaymentRepository.class);
                var service=new PaymentService(repository,new IdempotencyRepository(sql),mapper,mock(LedgerService.class),mock(OutboxService.class));
                var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                var replay=tx.execute(status -> service.createPayment("legacy-upgrade",request));
                assertThat(replay.created()).isFalse(); assertThat(replay.payment()).isEqualTo(mapper.readValue(snapshot,PaymentResponse.class));
                verifyNoInteractions(repository);
                assertThat(sql.queryForObject("SELECT enabled FROM merchants WHERE id=?",Boolean.class,MerchantIdentity.LEGACY_ID)).isFalse();
            } finally { connection.setSchema(original); sql.execute("DROP SCHEMA \""+schema+"\" CASCADE"); }
        }
    }

    private void provision(UUID id,String username,String hash) {
        jdbc.update("""
                INSERT INTO merchants(id,username,password_hash,enabled) VALUES (?,?,?,true)
                ON CONFLICT (id) DO UPDATE SET password_hash=EXCLUDED.password_hash,enabled=true
                """,id,username,hash);
    }
    private static String body(String amount) { return "{\"merchantReference\":\"PRIVATE-MERCHANT-ORDER\",\"amount\":"+amount+",\"currency\":\"INR\"}"; }
    private MvcResult createResult(String username,String password,String key,String amount) throws Exception {
        return mvc.perform(post("/api/v1/payments").with(httpBasic(username,password)).with(csrf())
                .header("Idempotency-Key",key).contentType(APPLICATION_JSON).content(body(amount))).andReturn();
    }
    private UUID create(String username,String password,String key,String amount,int expected) throws Exception {
        var result=createResult(username,password,key,amount);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
        return expected==201 || expected==200 ? id(result) : null;
    }
    private UUID id(MvcResult result) throws Exception { return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText()); }
    private void action(UUID id,String action) throws Exception {
        mvc.perform(post("/api/v1/payments/"+id+"/"+action).with(httpBasic("merchant-a",PASSWORD_A)).with(csrf())).andExpect(status().isOk());
    }
    private List<BigDecimal> balances(String username,String password) throws Exception {
        var result=mvc.perform(get("/api/v1/ledger/accounts/INR/balances").with(httpBasic(username,password))).andExpect(status().isOk()).andReturn();
        var values=new ArrayList<BigDecimal>();
        for (var row:mapper.readTree(result.getResponse().getContentAsString())) values.add(row.path("balance").decimalValue());
        return values;
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private Map<String,String> image() {
        var result=new TreeMap<String,String>();
        for (String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename<>'flyway_schema_history'",String.class))
            result.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM "+table+" t",String.class));
        return result;
    }
}
