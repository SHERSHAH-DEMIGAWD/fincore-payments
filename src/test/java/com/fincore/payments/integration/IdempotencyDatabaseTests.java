package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.repository.IdempotencyRepository;
import com.fincore.payments.repository.PaymentRepository;
import com.fincore.payments.service.PaymentRequestFingerprint;
import com.fincore.payments.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.fincore.payments.service.IdempotencyKey.HEADER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static com.fincore.payments.support.MerchantMvcRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Same real-database assertions run against Testcontainers, or an explicitly supplied local test DB. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class IdempotencyDatabaseTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentRepository payments;
    @Autowired IdempotencyRepository idempotency;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService service;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_positions, consumer_event_failures, outbox_requeue_audit, payment_event_projections, consumed_payment_events, local_payment_event_inbox, payment_outbox, ledger_entries, ledger_transactions, ledger_accounts, idempotency_records, payments");
    }

    @Test
    void firstRequestCreatesOnePaymentAndCompleteRecord() throws Exception {
        var response = create("new-key", body("ORDER-123", "1499.99", "INR"), 201);
        var id = id(response);
        assertCounts(1);
        assertThat(response.getResponse().getHeader("Location")).endsWith("/api/v1/payments/" + id);
        assertThat(jdbc.queryForObject("SELECT payment_id FROM idempotency_records", UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency_records", String.class))
                .isEqualTo(response.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM idempotency_records", String.class))
                .isEqualTo(PaymentRequestFingerprint.of(request()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Long.class)).isEqualTo(1);
    }

    @Test
    void twentyRetriesReturnExactOriginalCreationResult() throws Exception {
        String body = body("ORDER-123", "1499.99", "INR");
        String original = create("retry-key", body, 201).getResponse().getContentAsString();
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(create("retry-key", body, 200).getResponse().getContentAsString()).isEqualTo(original);
        }
        assertCounts(1);
    }

    @Test
    void replayKeepsCreationSnapshotAfterLifecycleChanges() throws Exception {
        String body = body("ORDER-123", "1499.99", "INR");
        var first = create("snapshot-key", body, 201);
        UUID id = id(first);
        for (String action : List.of("authorize", "capture", "refund")) {
            mvc.perform(post("/api/v1/payments/{id}/{action}", id, action)).andExpect(status().isOk());
        }
        String replay = create("snapshot-key", body, 200).getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first.getResponse().getContentAsString());
        assertThat(mapper.readTree(replay).get("status").asText()).isEqualTo("CREATED");
        mvc.perform(get("/api/v1/payments/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertCounts(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "currency", "reference"})
    void conflictingPayloadReturns409WithoutCreatingAnotherPayment(String field) throws Exception {
        create("conflict-key", body("ORDER-123", "1499.99", "INR"), 201);
        String changed = body(field.equals("reference") ? "ORDER-OTHER" : "ORDER-123",
                field.equals("amount") ? "5000.00" : "1499.99", field.equals("currency") ? "USD" : "INR");
        var response = create("conflict-key", changed, 409);
        assertThat(mapper.readTree(response.getResponse().getContentAsString()).get("error").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");
        assertCounts(1);
    }

    @Test
    void invalidKeysReturn400WithoutRows() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(APPLICATION_JSON)
                        .content(body("ORDER-123", "10.00", "INR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
        for (String key : List.of("", " ", "has space", "a".repeat(256), "clé")) {
            create(key, body("ORDER-123", "10.00", "INR"), 400);
        }
        assertCounts(0);
    }

    @Test
    void acceptsMaximumLengthKey() throws Exception {
        create("a".repeat(255), body("ORDER-123", "10", "INR"), 201);
        assertCounts(1);
    }

    @Test
    void decimalRepresentationsAndJsonLayoutAreEquivalent() throws Exception {
        String original = create("amount-key", body("ORDER-123", "1000", "INR"), 201)
                .getResponse().getContentAsString();
        for (String amount : List.of("1000.0", "1000.00")) {
            String reordered = "{ \"currency\": \"INR\", \"amount\": " + amount
                    + ", \"merchantReference\": \"ORDER-123\" }";
            assertThat(create("amount-key", reordered, 200).getResponse().getContentAsString()).isEqualTo(original);
        }
        assertCounts(1);
    }

    @Test
    void unsupportedPrecisionIsRejectedBeforeClaimAndKeyCanBeReused() throws Exception {
        create("precision-key", body("ORDER-123", "10.001", "INR"), 400);
        assertCounts(0);
        create("precision-key", body("ORDER-123", "10.00", "INR"), 201);
        assertThat(payments.findAll().getFirst().getAmount()).isEqualTo(new BigDecimal("10.00"));
        assertCounts(1);
    }

    @Test
    void invalidCurrencyCannotBypassValidationOnReplay() throws Exception {
        create("currency-key", body("ORDER-123", "10", "INR"), 201);
        create("currency-key", body("ORDER-123", "10", "inr"), 400);
        assertCounts(1);
    }

    @Test
    void differentKeysCreateSeparatePaymentsForSameBody() throws Exception {
        UUID first = id(create("key-A", body("ORDER-123", "10", "INR"), 201));
        UUID second = id(create("key-B", body("ORDER-123", "10", "INR"), 201));
        assertThat(second).isNotEqualTo(first);
        assertCounts(2);
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOnePayment() throws Exception {
        int callers = 8;
        var ready = new CyclicBarrier(callers);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                futures.add(executor.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/payments").header(HEADER_NAME, "concurrent-key")
                            .contentType(APPLICATION_JSON).content(body("ORDER-RACE", "1000.00", "INR"))).andReturn();
                }));
            }
            List<MvcResult> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 200).count()).isEqualTo(callers - 1);
            String original = results.getFirst().getResponse().getContentAsString();
            UUID paymentId = id(results.getFirst());
            for (var result : results) {
                assertThat(result.getResponse().getContentAsString()).isEqualTo(original);
                assertThat(id(result)).isEqualTo(paymentId);
            }
            assertCounts(1);
            assertThat(payments.findById(paymentId)).isPresent();
        }
    }

    @Test
    void concurrentDifferentPayloadsProduceOneCreationAndOneConflict() throws Exception {
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (String amount : List.of("10.00", "20.00")) {
                futures.add(executor.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/payments").header(HEADER_NAME, "conflicting-race")
                            .contentType(APPLICATION_JSON).content(body("ORDER-RACE", amount, "INR"))).andReturn();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertCounts(1);
        }
    }

    @Test
    void failureAfterPaymentFlushRollsBackPaymentAndClaimTogether() throws Exception {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            service.createPayment("rollback-key", request());
            // Both inserts have executed against PostgreSQL, but neither may survive this failure.
            assertCounts(1);
            throw new IllegalStateException("Intentional failure after payment flush");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Intentional failure");
        assertCounts(0);
        create("rollback-key", body("ORDER-123", "1499.99", "INR"), 201);
        assertCounts(1);
    }

    @Test
    void databaseRejectsDuplicateKeyEvenWithoutApplicationCheck() {
        Payment payment = payments.saveAndFlush(Payment.create("ORDER-DIRECT", BigDecimal.TEN, "INR"));
        String sql = """
                INSERT INTO idempotency_records
                    (idempotency_key, request_fingerprint, payment_id, response_body, created_at)
                VALUES ('duplicate-key', ?, ?, '{}', CURRENT_TIMESTAMP)
                """;
        jdbc.update(sql, "a".repeat(64), payment.getId());
        assertThatThrownBy(() -> jdbc.update(sql, "b".repeat(64), payment.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCounts(1);
    }

    @Test
    void deferredForeignKeyForbidsCommittingClaimWithoutPayment() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            assertThat(idempotency.claim("orphan-key", "a".repeat(64), UUID.randomUUID(), "{}", Instant.now()))
                    .isTrue();
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertCounts(0);
    }

    private void assertCounts(long count) {
        assertThat(payments.count()).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class)).isEqualTo(count);
    }

    private MvcResult create(String key, String body, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/v1/payments").header(HEADER_NAME, key)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private UUID id(MvcResult response) throws Exception {
        return UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest("ORDER-123", new BigDecimal("1499.99"), "INR");
    }

    private String body(String reference, String amount, String currency) {
        return "{\"merchantReference\":\"" + reference + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
    }
}
