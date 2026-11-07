package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.repository.PaymentRepository;
import com.fincore.payments.service.DatabasePaymentEventPublisher;
import com.fincore.payments.service.OutboxDeliveryWorker;
import com.fincore.payments.service.OutboxDispatcher;
import com.fincore.payments.service.OutboxFailureRecorder;
import com.fincore.payments.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static com.fincore.payments.support.MerchantMvcRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real-database outbox assertions; scheduling is disabled so each dispatch is explicitly controlled. */
@SpringBootTest(properties = {
        "fincore.outbox.enabled=false",
        "fincore.outbox.initial-backoff-ms=60000",
        "fincore.outbox.max-backoff-ms=60000"
})
@AutoConfigureMockMvc
abstract class OutboxDatabaseTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentRepository payments;
    @Autowired PaymentService service;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxDeliveryWorker worker;
    @Autowired OutboxFailureRecorder failures;
    @MockitoSpyBean DatabasePaymentEventPublisher publisher;
    DatabasePaymentEventPublisher publisherSpy;

    @BeforeEach
    void resetDisposableDatabase() {
        // Stub the Mockito spy beneath the transaction proxy. Invoking the MANDATORY proxy
        // while configuring a stub would itself require an active business transaction.
        publisherSpy = AopTestUtils.getUltimateTargetObject(publisher);
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_positions, consumer_event_failures, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events,
                    local_payment_event_inbox, payment_outbox, ledger_entries,
                    ledger_transactions, ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void everyCommittedLifecycleChangeHasOneVersionedSnapshot() throws Exception {
        UUID id = create("lifecycle", "lifecycle-key");
        action(id, "authorize", 200);
        action(id, "capture", 200);
        action(id, "refund", 200);

        JsonNode events = events(id);
        assertThat(events.size()).isEqualTo(4);
        List<String> types = List.of("PAYMENT_CREATED", "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED", "PAYMENT_REFUNDED");
        List<String> states = List.of("CREATED", "AUTHORIZED", "CAPTURED", "REFUNDED");
        for (int version = 0; version < 4; version++) {
            JsonNode delivery = events.get(version);
            JsonNode event = delivery.get("event");
            assertThat(event.get("eventId").asText()).isNotBlank();
            assertThat(event.get("eventType").asText()).isEqualTo(types.get(version));
            assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(event.get("paymentId").asText()).isEqualTo(id.toString());
            assertThat(event.get("paymentVersion").asLong()).isEqualTo(version);
            assertThat(event.get("occurredAt").asText()).isNotBlank();
            assertThat(event.get("data").get("id").asText()).isEqualTo(id.toString());
            assertThat(event.get("data").get("status").asText()).isEqualTo(states.get(version));
            assertThat(event.get("data").get("amount").decimalValue()).isEqualByComparingTo("10.00");
            assertThat(delivery.get("status").asText()).isEqualTo("PENDING");
            assertThat(delivery.get("attempts").asInt()).isZero();
        }
        assertCounts(4, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(2);
    }

    @Test
    void failTransitionRecordsFailureEvent() throws Exception {
        UUID id = create("failed", "failed-key");
        action(id, "authorize", 200);
        action(id, "fail", 200);
        var events = events(id);
        assertThat(events.size()).isEqualTo(3);
        assertThat(events.get(2).get("event").get("eventType").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(events.get(2).get("event").get("data").get("status").asText()).isEqualTo("FAILED");
        assertCounts(3, 0);
    }

    @Test
    void creationReplayConflictsAndIllegalTransitionsNeverCreateEvents() throws Exception {
        UUID id = create("retry", "retry-key");
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "retry-key")
                        .contentType(APPLICATION_JSON).content(body("retry")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "retry-key")
                        .contentType(APPLICATION_JSON).content(body("different-reference")))
                .andExpect(status().isConflict());
        action(id, "refund", 409);
        assertCounts(1, 0);
        action(id, "authorize", 200);
        action(id, "authorize", 409);
        action(id, "capture", 200);
        action(id, "capture", 409);
        assertCounts(3, 0);
    }

    @Test
    void rollbackRemovesPaymentIdempotencyLedgerAndOutboxTogether() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            var result = service.createPayment("rollback-key",
                    new CreatePaymentRequest("ROLLBACK", new BigDecimal("10.00"), "INR"));
            service.authorizePayment(result.payment().id());
            service.capturePayment(result.payment().id());
            assertCounts(3, 0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(1);
            throw new IllegalStateException("Intentional payment/ledger/outbox rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Intentional");
        assertThat(payments.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isZero();
        assertCounts(0, 0);
    }

    @Test
    void databaseRejectsDuplicatePaymentVersions() throws Exception {
        create("db-unique", "db-unique-key");
        UUID duplicateId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_outbox
                    (event_id, payment_id, payment_version, event_type, schema_version, occurred_at, payload,
                     status, attempts, next_attempt_at)
                SELECT ?, payment_id, payment_version, event_type, schema_version, occurred_at,
                    jsonb_set(payload::jsonb, '{eventId}', to_jsonb(CAST(? AS uuid)))::text,
                    'PENDING', 0, CURRENT_TIMESTAMP FROM payment_outbox
                """, duplicateId, duplicateId))
                .isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(exception -> {
                    Throwable root = exception;
                    while (root.getCause() != null) root = root.getCause();
                    assertThat(((SQLException) root).getSQLState()).isEqualTo("23505");
                });
        assertCounts(1, 0);
    }

    @Test
    void eventEnvelopeCannotBeMutated() throws Exception {
        create("immutable", "immutable-key");
        String original = jdbc.queryForObject("SELECT payload FROM payment_outbox", String.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_outbox SET payload = '{}'"))
                .isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("SELECT payload FROM payment_outbox", String.class)).isEqualTo(original);
    }

    @Test
    void missingPaymentEventHistoryReturns404() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}/events", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        assertCounts(0, 0);
    }

    @Test
    void dispatchDeliversExactEnvelopesToLocalInboxAndAcknowledgesOnce() throws Exception {
        UUID id = create("delivered", "delivered-key");
        action(id, "authorize", 200);
        action(id, "capture", 200);
        action(id, "refund", 200);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(4);
        assertCounts(4, 4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE status = 'DELIVERED' AND attempts = 1",
                Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment_outbox source JOIN local_payment_event_inbox inbox USING (event_id)
                WHERE source.payload = inbox.payload AND source.delivered_at IS NOT NULL
                """, Long.class)).isEqualTo(4);
        assertThat(dispatcher.dispatchBatch()).isZero();
        assertCounts(4, 4);
    }

    @Test
    void localReceiverDeduplicatesSameEventId() throws Exception {
        create("receiver-dedup", "receiver-dedup-key");
        String payload = jdbc.queryForObject("SELECT payload FROM payment_outbox", String.class);
        PaymentEvent event = mapper.readValue(payload, PaymentEvent.class);
        new TransactionTemplate(transactionManager).execute(transaction -> {
            publisher.publish(event);
            publisher.publish(event);
            return null;
        });
        assertCounts(1, 1);
        // A subsequent source delivery can safely repeat that receiver call and acknowledge the source.
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void failuresUseBoundedBackoffAndBecomeDeadAtConfiguredLimit() throws Exception {
        UUID blocked = create("dead-payment", "dead-key");
        action(blocked, "authorize", 200);
        doThrow(new IllegalStateException("private payment details must not escape"))
                .when(publisherSpy).publish(any(PaymentEvent.class));

        for (int attempt = 1; attempt <= 5; attempt++) {
            if (attempt > 1) makeReady(blocked, 0);
            Instant before = databaseNow();
            assertThat(dispatcher.dispatchBatch()).isZero();
            var original = events(blocked).get(0);
            assertThat(original.get("attempts").asInt()).isEqualTo(attempt);
            assertThat(original.get("status").asText()).isEqualTo(attempt == 5 ? "DEAD" : "PENDING");
            assertThat(original.get("lastErrorCode").asText()).isEqualTo("PUBLISH_FAILED");
            if (attempt < 5) {
                Instant retryAt = Instant.parse(original.get("nextAttemptAt").asText());
                assertThat(retryAt).isAfter(before);
                assertThat(Duration.between(before, retryAt)).isLessThan(Duration.ofSeconds(65));
            }
            assertCounts(2, 0);
        }

        doCallRealMethod().when(publisherSpy).publish(any(PaymentEvent.class));
        create("healthy-payment", "healthy-key");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertCounts(3, 1);
        var history = events(blocked);
        assertThat(history.get(0).get("status").asText()).isEqualTo("DEAD");
        assertThat(history.get(1).get("status").asText()).isEqualTo("PENDING");
        assertThat(history.get(1).get("attempts").asInt()).isZero();
    }

    @Test
    void pendingEarlierVersionBlocksLaterVersionsUntilSuccessfulRetry() throws Exception {
        UUID id = create("ordered-payment", "ordered-key");
        action(id, "authorize", 200);
        doThrow(new IllegalStateException("publisher unavailable"))
                .when(publisherSpy).publish(any(PaymentEvent.class));
        assertThat(dispatcher.dispatchBatch()).isZero();
        jdbc.update("""
                UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                WHERE payment_id = ? AND payment_version = 0
                """, id);
        doCallRealMethod().when(publisherSpy).publish(any(PaymentEvent.class));
        assertThat(dispatcher.dispatchBatch()).isZero();
        assertCounts(2, 0);
        assertThat(events(id).get(1).get("attempts").asInt()).isZero();

        makeReady(id, 0);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(2);
        assertCounts(2, 2);
        assertThat(events(id).get(0).get("attempts").asInt()).isEqualTo(2);
        assertThat(events(id).get(1).get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void databasePublishFailureIsRolledBackBeforeRetryMetadataIsRecorded() throws Exception {
        UUID id = create("db-failure", "db-failure-key");
        doAnswer(invocation -> {
            jdbc.execute("SELECT 1 / 0");
            return null;
        }).when(publisherSpy).publish(any(PaymentEvent.class));
        assertThat(dispatcher.dispatchBatch()).isZero();
        var first = events(id).get(0);
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("attempts").asInt()).isEqualTo(1);
        assertThat(first.get("lastErrorCode").asText()).isEqualTo("PUBLISH_FAILED");
        assertCounts(1, 0);

        doCallRealMethod().when(publisherSpy).publish(any(PaymentEvent.class));
        makeReady(id, 0);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void rolledBackDeliveryRemainsPendingAndCanBeRetriedByAnotherWorker() throws Exception {
        UUID id = create("rollback-delivery", "rollback-delivery-key");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Failure after receiver insert before delivery commit");
        }).when(publisherSpy).publish(any(PaymentEvent.class));

        // Calling the transactional worker directly avoids outer failure bookkeeping: its failed
        // delivery transaction must leave neither a receiver row nor a source acknowledgement.
        assertThatThrownBy(() -> worker.dispatchNext()).isInstanceOf(RuntimeException.class);
        assertCounts(1, 0);
        assertThat(events(id).get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(events(id).get(0).get("attempts").asInt()).isZero();
        doCallRealMethod().when(publisherSpy).publish(any(PaymentEvent.class));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    void concurrentDispatchersDeliverEachSourceOnce() throws Exception {
        int sourceEvents = 8;
        for (int payment = 0; payment < sourceEvents; payment++) {
            create("parallel-" + payment, "parallel-key-" + payment);
        }
        int workers = 4;
        var ready = new CyclicBarrier(workers);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return dispatcher.dispatchBatch();
                }));
            }
            int delivered = 0;
            for (var future : futures) delivered += future.get(30, TimeUnit.SECONDS);
            assertThat(delivered).isEqualTo(sourceEvents);
        }
        assertCounts(sourceEvents, sourceEvents);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE status = 'DELIVERED' AND attempts = 1",
                Long.class)).isEqualTo(sourceEvents);
    }

    @Test
    void staleFailureCannotOverwriteSuccessfulDelivery() throws Exception {
        UUID id = create("stale-failure", "stale-failure-key");
        UUID eventId = UUID.fromString(events(id).get(0).get("event").get("eventId").asText());
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        JsonNode delivered = events(id).get(0);
        assertThat(delivered.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.get("attempts").asInt()).isEqualTo(1);

        assertThat(failures.recordFailure(eventId, 0)).isFalse();
        assertThat(events(id).get(0)).isEqualTo(delivered);
        assertCounts(1, 1);
    }

    @Test
    void workerCannotPublishAnUncommittedEvent() {
        new TransactionTemplate(transactionManager).execute(transaction -> {
            service.createPayment("uncommitted-key",
                    new CreatePaymentRequest("UNCOMMITTED", new BigDecimal("10.00"), "INR"));
            assertCounts(1, 0);
            // The REQUIRES_NEW worker uses another transaction/connection. The event must be
            // invisible there until this enclosing payment transaction actually commits.
            assertThat(worker.dispatchNext()).isFalse();
            assertCounts(1, 0);
            return null;
        });

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("DELIVERED");
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private void makeReady(UUID id, long version) {
        jdbc.update("""
                UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE payment_id = ? AND payment_version = ? AND status = 'PENDING'
                """, id, version);
    }

    private UUID create(String reference, String key) throws Exception {
        var response = mvc.perform(post("/api/v1/payments").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(body(reference)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    private void action(UUID id, String action, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/payments/{id}/{action}", id, action)).andExpect(status().is(expectedStatus));
    }

    private JsonNode events(UUID id) throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/payments/{id}/events", id)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private void assertCounts(long source, long inbox) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox", Long.class)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM local_payment_event_inbox", Long.class)).isEqualTo(inbox);
    }

    private String body(String reference) {
        return "{\"merchantReference\":\"" + reference + "\",\"amount\":10.00,\"currency\":\"INR\"}";
    }
}
