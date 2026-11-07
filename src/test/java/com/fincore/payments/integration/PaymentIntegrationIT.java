package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static com.fincore.payments.support.MerchantMvcRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PaymentIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PaymentRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanPayments() {
        jdbc.execute("TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_positions, consumer_event_failures, outbox_requeue_audit, payment_event_projections, consumed_payment_events, local_payment_event_inbox, payment_outbox, ledger_entries, ledger_transactions, ledger_accounts, idempotency_records, payments");
    }

    @Test
    void createsPaymentAndPersistsItInPostgres() throws Exception {
        UUID id = createPayment();
        Payment stored = repository.findById(id).orElseThrow();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(stored.getMerchantReference()).isEqualTo("ORDER-123");
        assertThat(stored.getAmount()).isEqualTo(new BigDecimal("1499.99"));
        assertThat(stored.getCurrency()).isEqualTo("INR");
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(stored.getVersion()).isZero();
        mvc.perform(get("/api/v1/payments/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amount").value(1499.99));
    }

    @Test
    void runsLifecycleAndRejectsTransitionFromRefunded() throws Exception {
        UUID id = createPayment();
        transition(id, "authorize", "AUTHORIZED");
        transition(id, "capture", "CAPTURED");
        transition(id, "refund", "REFUNDED");
        mvc.perform(post("/api/v1/payments/{id}/capture", id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ILLEGAL_PAYMENT_STATE_TRANSITION"));
        Payment stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(stored.getVersion()).isEqualTo(3L);
    }

    @Test
    void illegalTransitionLeavesStoredPaymentUnchanged() throws Exception {
        UUID id = createPayment();
        Payment before = repository.findById(id).orElseThrow();
        mvc.perform(post("/api/v1/payments/{id}/refund", id)).andExpect(status().isConflict());
        Payment after = repository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test
    void failsCreatedPaymentAndRejectsFurtherOperations() throws Exception {
        UUID id = createPayment();
        transition(id, "fail", "FAILED");
        mvc.perform(post("/api/v1/payments/{id}/authorize", id)).andExpect(status().isConflict());
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void failsAuthorizedPayment() throws Exception {
        UUID id = createPayment();
        transition(id, "authorize", "AUTHORIZED");
        transition(id, "fail", "FAILED");
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void returns404ForMissingPaymentAndAllActions() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/v1/payments/{id}", id)).andExpect(status().isNotFound());
        for (String action : List.of("authorize", "capture", "fail", "refund")) {
            mvc.perform(post("/api/v1/payments/{id}/{action}", id, action))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "null"})
    void invalidAmountReturns400WithoutCreatingRow(String amount) throws Exception {
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"ORDER-123\",\"amount\":" + amount + ",\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.amount").exists());
        assertThat(repository.count()).isZero();
    }

    @Test
    void flywayMigrationAndDatabaseConstraintsAreActive() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success", Long.class))
                .isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payments (id, merchant_reference, amount, currency, status, created_at, updated_at, version)
                VALUES (?, 'ORDER-INVALID', -1.00, 'INR', 'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void optimisticLockingRejectsOneOfTwoConcurrentUpdates() throws Exception {
        UUID id = repository.saveAndFlush(Payment.create("ORDER-RACE", BigDecimal.TEN, "INR")).getId();
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);

        // Separate worker threads guarantee independent transactions/persistence contexts.
        // Both must read version 0 before either flushes; no timing sleeps or mocked database.
        try (var executor = Executors.newFixedThreadPool(2)) {
            var authorize = executor.submit(updateInTransaction(id, true, bothHaveRead));
            var fail = executor.submit(updateInTransaction(id, false, bothHaveRead));
            List<String> results = List.of(authorize.get(20, TimeUnit.SECONDS), fail.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter("CONFLICT"::equals).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> !result.equals("CONFLICT")).count()).isEqualTo(1);
            Payment stored = repository.findById(id).orElseThrow();
            assertThat(stored.getVersion()).isEqualTo(1L);
            assertThat(stored.getStatus()).isIn(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
            assertThat(results).contains(stored.getStatus().name());
            assertThat(repository.count()).isEqualTo(1);
        }
    }

    private Callable<String> updateInTransaction(UUID id, boolean authorize, CyclicBarrier barrier) {
        return () -> {
            try {
                return new TransactionTemplate(transactionManager).execute(transaction -> {
                    Payment payment = repository.findById(id).orElseThrow();
                    assertThat(payment.getVersion()).isZero();
                    awaitBothReads(barrier);
                    if (authorize) {
                        payment.authorize();
                    } else {
                        payment.fail();
                    }
                    repository.flush();
                    return payment.getStatus().name();
                });
            } catch (OptimisticLockingFailureException conflict) {
                return "CONFLICT";
            }
        };
    }

    private void awaitBothReads(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while synchronizing database transactions.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not synchronize database transactions.", exception);
        }
    }

    private UUID createPayment() throws Exception {
        var result = mvc.perform(post("/api/v1/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("""
                {"merchantReference":"ORDER-123","amount":1499.99,"currency":"INR"}
                """))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CREATED")).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private void transition(UUID id, String action, String expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/payments/{id}/{action}", id, action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expectedStatus));
    }
}
