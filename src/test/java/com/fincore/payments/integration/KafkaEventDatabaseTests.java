package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.service.PaymentEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
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
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL consumer assertions, independent of any Kafka broker or source payment. */
@SpringBootTest(properties = {"fincore.outbox.enabled=false", "fincore.events.transport=local"})
@AutoConfigureMockMvc
abstract class KafkaEventDatabaseTests {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired PaymentEventConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_positions, consumer_event_failures, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events,
                    local_payment_event_inbox, payment_outbox, ledger_entries,
                    ledger_transactions, ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void externalSnapshotCreatesAnInternalProjectionWithoutInventingPublicOwnership() throws Exception {
        var event = event(UUID.randomUUID(), 2, PaymentStatus.CAPTURED);

        assertThat(consumer.consume(event)).isTrue();
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT consumer_name FROM consumed_payment_events", String.class))
                .isEqualTo(PaymentEventConsumer.CONSUMER_NAME);
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT payload FROM consumed_payment_events", String.class)))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(event)));
        var projection=consumer.getProjection(event.paymentId());
        assertThat(projection.paymentId()).isEqualTo(event.paymentId());
        assertThat(projection.lastEventId()).isEqualTo(event.eventId());
        assertThat(projection.paymentVersion()).isEqualTo(2);
        assertThat(projection.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(projection.merchantReference()).isEqualTo(event.data().merchantReference());
        assertThat(projection.amount()).isEqualByComparingTo("10.00");
        assertThat(projection.currency()).isEqualTo("INR");
        assertThat(projection.occurredAt()).isEqualTo(event.occurredAt());
        assertThat(projection.projectedAt()).isNotNull();
        mvc.perform(get("/api/v1/payments/{id}/projection", event.paymentId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        assertNoFinancialWrites();
    }

    @Test
    void exactRedeliveryDoesNotChangeProjectionOrCreateAnotherReceipt() {
        var event = event(UUID.randomUUID(), 0, PaymentStatus.CREATED);
        assertThat(consumer.consume(event)).isTrue();
        var before = jdbc.queryForMap("SELECT * FROM payment_event_projections");

        assertThat(consumer.consume(event)).isFalse();

        assertCounts(1, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_event_projections")).isEqualTo(before);
        assertNoFinancialWrites();
    }

    @Test
    void concurrentRedeliveryAppliesExactlyOneDatabaseEffect() throws Exception {
        var event = event(UUID.randomUUID(), 2, PaymentStatus.CAPTURED);
        int workers = 4;
        var ready = new CyclicBarrier(workers);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                results.add(executor.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return consumer.consume(event);
                }));
            }
            int applied = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) applied++;
            assertThat(applied).isEqualTo(1);
        }
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT last_event_id FROM payment_event_projections", UUID.class))
                .isEqualTo(event.eventId());
        assertNoFinancialWrites();
    }

    @Test
    void concurrentDifferentVersionsKeepEveryReceiptAndProjectTheHighestVersion() throws Exception {
        UUID paymentId = UUID.randomUUID();
        var snapshots = List.of(event(paymentId, 0, PaymentStatus.CREATED),
                event(paymentId, 1, PaymentStatus.AUTHORIZED), event(paymentId, 2, PaymentStatus.CAPTURED),
                event(paymentId, 3, PaymentStatus.REFUNDED));
        var ready = new CyclicBarrier(snapshots.size());
        try (var executor = Executors.newFixedThreadPool(snapshots.size())) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var snapshot : snapshots) {
                results.add(executor.submit(() -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return consumer.consume(snapshot);
                }));
            }
            for (var result : results) assertThat(result.get(20, TimeUnit.SECONDS)).isTrue();
        }

        assertCounts(4, 1);
        var projection = consumer.getProjection(paymentId);
        assertThat(projection.paymentVersion()).isEqualTo(3);
        assertThat(projection.lastEventId()).isEqualTo(snapshots.getLast().eventId());
        assertThat(projection.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertNoFinancialWrites();
    }

    @Test
    void databaseRejectsReceiptUpdatesAndDeletesWithoutChangingHistory() {
        var event = event(UUID.randomUUID(), 0, PaymentStatus.CREATED);
        consumer.consume(event);
        var originalReceipt = jdbc.queryForMap("SELECT * FROM consumed_payment_events");
        var originalProjection = jdbc.queryForMap("SELECT * FROM payment_event_projections");

        for (String sql : List.of("UPDATE consumed_payment_events SET payload = payload WHERE event_id = ?",
                "DELETE FROM consumed_payment_events WHERE event_id = ?")) {
            assertThatThrownBy(() -> jdbc.update(sql, event.eventId()))
                    .satisfies(failure -> assertCheckViolation(failure, "immutable"));
        }

        assertCounts(1, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM consumed_payment_events")).isEqualTo(originalReceipt);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_event_projections")).isEqualTo(originalProjection);
    }

    @Test
    void databaseRejectsMissingEnvelopeMetadataRatherThanAcceptingSqlNullChecks() throws Exception {
        var event = event(UUID.randomUUID(), 0, PaymentStatus.CREATED);
        String payload = mapper.writeValueAsString(event);
        for (String missingField : List.of("eventId", "paymentId", "schemaVersion", "eventType")) {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO consumed_payment_events
                        (consumer_name, event_id, payment_id, payment_version, event_type, schema_version, payload)
                    VALUES (?, ?, ?, ?, ?, ?, (CAST(? AS jsonb) - CAST(? AS text))::text)
                    """, PaymentEventConsumer.CONSUMER_NAME, event.eventId(), event.paymentId(), event.paymentVersion(),
                    event.eventType().name(), event.schemaVersion(), payload, missingField))
                    .satisfies(failure -> assertCheckViolation(failure, "consumed_payment_events_payload_metadata"));
        }

        assertCounts(0, 0);
        assertNoFinancialWrites();
    }

    @Test
    void reusedEventIdWithChangedSnapshotIsRejectedWithoutChangingStoredData() {
        var original = event(UUID.randomUUID(), 1, PaymentStatus.AUTHORIZED);
        consumer.consume(original);
        var before = jdbc.queryForMap("SELECT * FROM payment_event_projections");
        var changedData = new PaymentResponse(original.paymentId(), "ALTERED-REFERENCE", new BigDecimal("10.00"),
                "INR", original.data().status(), original.data().createdAt(), original.data().updatedAt());
        var changed = new PaymentEvent(original.eventId(), original.eventType(), 1, original.paymentId(),
                original.paymentVersion(), original.occurredAt(), changedData);

        assertThatThrownBy(() -> consumer.consume(changed)).isInstanceOf(RuntimeException.class);

        assertCounts(1, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_event_projections")).isEqualTo(before);
    }

    @Test
    void anotherEventIdCannotClaimTheSamePaymentVersion() {
        var original = event(UUID.randomUUID(), 2, PaymentStatus.CAPTURED);
        consumer.consume(original);
        var conflicting = new PaymentEvent(UUID.randomUUID(), original.eventType(), 1, original.paymentId(),
                original.paymentVersion(), original.occurredAt(), original.data());

        assertThatThrownBy(() -> consumer.consume(conflicting)).isInstanceOf(RuntimeException.class);

        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("SELECT last_event_id FROM payment_event_projections", UUID.class))
                .isEqualTo(original.eventId());
    }

    @Test
    void olderSnapshotIsRecordedWithoutRegressingTheLatestProjection() {
        UUID paymentId = UUID.randomUUID();
        var newest = event(paymentId, 2, PaymentStatus.CAPTURED);
        consumer.consume(newest);
        var before = jdbc.queryForMap("SELECT * FROM payment_event_projections");

        assertThat(consumer.consume(event(paymentId, 0, PaymentStatus.CREATED))).isTrue();
        assertThat(consumer.consume(event(paymentId, 1, PaymentStatus.AUTHORIZED))).isTrue();

        assertCounts(3, 1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_event_projections")).isEqualTo(before);
    }

    @Test
    void newerSnapshotsAdvanceTheProjectionAndKeepAllReceipts() {
        UUID paymentId = UUID.randomUUID();
        consumer.consume(event(paymentId, 0, PaymentStatus.CREATED));
        consumer.consume(event(paymentId, 1, PaymentStatus.AUTHORIZED));
        var captured = event(paymentId, 2, PaymentStatus.CAPTURED);

        assertThat(consumer.consume(captured)).isTrue();

        assertCounts(3, 1);
        var projection = consumer.getProjection(paymentId);
        assertThat(projection.lastEventId()).isEqualTo(captured.eventId());
        assertThat(projection.paymentVersion()).isEqualTo(2);
        assertThat(projection.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertNoFinancialWrites();
    }

    @Test
    void receiptAndProjectionRollBackTogetherAndCanBeRetried() {
        var event = event(UUID.randomUUID(), 2, PaymentStatus.CAPTURED);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            assertThat(consumer.consume(event)).isTrue();
            assertCounts(1, 1);
            throw new IllegalStateException("Intentional consumer transaction rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Intentional");

        assertCounts(0, 0);
        assertThat(consumer.consume(event)).isTrue();
        assertCounts(1, 1);
    }

    @Test
    void failedProjectionWriteRollsBackTheReceiptClaimBeforeRedelivery() {
        var event = event(UUID.randomUUID(), 2, PaymentStatus.CAPTURED);
        jdbc.execute("ALTER TABLE payment_event_projections ADD CONSTRAINT fincore_test_projection_failure CHECK (false)");
        try {
            assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(RuntimeException.class);
            assertCounts(0, 0);
        } finally {
            jdbc.execute("ALTER TABLE payment_event_projections DROP CONSTRAINT fincore_test_projection_failure");
        }

        assertThat(consumer.consume(event)).isTrue();
        assertCounts(1, 1);
        assertNoFinancialWrites();
    }

    @Test
    void unsupportedSchemaAndInconsistentEnvelopesLeaveNoConsumerState() {
        var valid = event(UUID.randomUUID(), 0, PaymentStatus.CREATED);
        var unsupported = new PaymentEvent(valid.eventId(), valid.eventType(), 2, valid.paymentId(),
                valid.paymentVersion(), valid.occurredAt(), valid.data());
        var wrongIdentity = new PaymentEvent(valid.eventId(), valid.eventType(), 1, UUID.randomUUID(),
                valid.paymentVersion(), valid.occurredAt(), valid.data());
        var wrongStatus = new PaymentEvent(valid.eventId(), PaymentEventType.PAYMENT_CAPTURED, 1, valid.paymentId(),
                valid.paymentVersion(), valid.occurredAt(), valid.data());
        var negativeVersion = new PaymentEvent(valid.eventId(), valid.eventType(), 1, valid.paymentId(),
                -1, valid.occurredAt(), valid.data());

        for (var malformed : List.of(unsupported, wrongIdentity, wrongStatus, negativeVersion)) {
            assertThatThrownBy(() -> consumer.consume(malformed)).isInstanceOf(RuntimeException.class);
        }
        assertCounts(0, 0);
        assertNoFinancialWrites();
    }

    @Test
    void unknownPaymentDoesNotExposeConsumerProjectionState() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}/projection", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
        assertCounts(0, 0);
    }

    private PaymentEvent event(UUID paymentId, long version, PaymentStatus status) {
        Instant updatedAt = CREATED_AT.plusSeconds(version);
        var data = new PaymentResponse(paymentId, "EXTERNAL-" + paymentId, new BigDecimal("10.00"),
                "INR", status, CREATED_AT, updatedAt);
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.from(status), 1, paymentId,
                version, updatedAt, data);
    }

    private void assertCounts(long receipts, long projections) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events", Long.class)).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections", Long.class)).isEqualTo(projections);
    }

    private void assertCheckViolation(Throwable failure, String messageFragment) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOf(SQLException.class).hasMessageContaining(messageFragment);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("23514");
    }

    private void assertNoFinancialWrites() {
        for (String table : List.of("payments", "idempotency_records", "ledger_accounts", "ledger_transactions",
                "ledger_entries", "payment_outbox", "local_payment_event_inbox")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).as(table).isZero();
        }
    }
}
