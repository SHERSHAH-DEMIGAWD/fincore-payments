package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentReconciliation;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.dto.ConsumerRecoveryCommand;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.repository.ConsumerFailureRepository;
import com.fincore.payments.repository.OutboxRepository;
import com.fincore.payments.repository.PaymentRepository;
import com.fincore.payments.repository.PaymentEventConsumerRepository;
import com.fincore.payments.repository.ProjectionReconciliationRepository;
import com.fincore.payments.service.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.fincore.payments.dto.ReconciliationFinding.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

@SpringBootTest(properties = {"fincore.events.transport=local", "fincore.outbox.enabled=false"})
abstract class ProjectionReconciliationDatabaseTests {
    @Autowired ProjectionReconciliationService reconciliation;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEventConsumer consumer;
    @Autowired PaymentEventConsumerRepository receipts;
    @Autowired OutboxRepository outbox;
    @Autowired ConsumerFailureService failures;
    @Autowired ConsumerRecoveryService recovery;
    @Autowired OutboxFailureRecorder outboxFailures;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean ProjectionReconciliationRepository repository;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_failures, consumer_event_positions,
                    outbox_requeue_audit, payment_event_projections, consumed_payment_events,
                    local_payment_event_inbox, payment_outbox, ledger_entries, ledger_transactions,
                    ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void emptyReportDoesNotCertifyHistoryOrInventPaymentRows() {
        var report = reconciliation.reconcile(null, null, 50);
        assertThat(report.totalCandidates()).isZero();
        assertThat(report.payments()).isEmpty();
        assertThat(report.historicalCompleteness()).isEqualTo("NOT_CERTIFIED");
        assertThat(report.consumerStorageScope()).isEqualTo("LOGICAL_CONSUMER_ACROSS_SOURCE_NAMESPACES");
        assertThat(report.pageStatus()).isEqualTo("NO_OBSERVATIONS_ON_PAGE");
        assertThat(report.hasMore()).isFalse();
        assertThat(report.nextAfterPaymentId()).isNull();
    }

    @Test
    void newUnconsumedPaymentReportsExpectedAsynchronousWorkAndReplayInput() {
        UUID id = create();
        var row = row(id);
        assertThat(row.findings()).containsExactlyInAnyOrder(PROJECTION_MISSING, SOURCE_RECEIPTS_MISSING);
        assertThat(row.latestReceiptVersion()).isNull();
        assertThat(row.missingReceipts()).isEqualTo(1);
        assertThat(row.pendingSourceEvents()).isEqualTo(1);
        assertThat(row.latestSourceReplayCandidate()).isTrue();
    }

    @Test
    void fullyConsumedLifecycleHasMatchingSnapshotsAndContiguousRetainedVersions() throws Exception {
        UUID id = create();
        payments.authorizePayment(id);
        payments.capturePayment(id);
        consumeSource(id);

        var row = row(id);

        assertThat(row.findings()).isEmpty();
        assertThat(row.retainedHistoryContiguous()).isTrue();
        assertThat(row.sourceEventCount()).isEqualTo(3);
        assertThat(row.matchingReceipts()).isEqualTo(3);
        assertThat(row.sourceVersion()).isEqualTo(2);
        assertThat(row.projectionVersion()).isEqualTo(2);
        assertThat(row.latestSourceReplayCandidate()).isTrue();
    }

    @Test
    void consumerBehindIsDifferentFromMissingAndIdentityConflict() throws Exception {
        UUID id = create();
        consumer.consume(sourceEvent(id, 0));
        payments.authorizePayment(id);
        var row = row(id);
        assertThat(row.findings()).containsExactlyInAnyOrder(PROJECTION_BEHIND, SOURCE_RECEIPTS_MISSING);
        assertThat(row.missingReceipts()).isEqualTo(1);
        assertThat(row.conflictingReceipts()).isZero();
    }

    @Test
    void legacyPaymentWithoutAnySourceEventsHasNoInventedRebuildInput() {
        var payment = paymentRepository.saveAndFlush(Payment.create("PRIVATE-REFERENCE", BigDecimal.TEN, "INR"));
        var row = row(payment.getId());
        assertThat(row.findings()).contains(NO_SOURCE_EVENTS, CURRENT_SOURCE_EVENT_MISSING, PROJECTION_MISSING);
        assertThat(row.latestSourceReplayCandidate()).isFalse();
        assertThat(row.latestSourceEventId()).isNull();
        assertThat(row.retainedHistoryContiguous()).isFalse();
    }

    @Test
    void historyBeginningAfterVersionZeroIsStillIncompleteWithMatchingLatestSnapshot() {
        var payment = paymentRepository.saveAndFlush(Payment.create("PRIVATE-REFERENCE", BigDecimal.TEN, "INR"));
        jdbc.update("UPDATE payments SET version = 2 WHERE id = ?", payment.getId());
        var event = external(payment.getId(), 2, payments.getPayment(payment.getId()));
        outbox.save(event);
        consumer.consume(event);
        var row = row(payment.getId());
        assertThat(row.findings()).containsExactly(HISTORY_STARTS_AFTER_ZERO);
        assertThat(row.retainedHistoryContiguous()).isFalse();
        assertThat(row.latestSourceReplayCandidate()).isTrue();
    }

    @Test
    void internalSourceVersionGapIsDetectedWithoutGeneratingMissingEvents() throws Exception {
        UUID id = create();
        jdbc.update("UPDATE payments SET version = 2 WHERE id = ?", id);
        outbox.save(external(id, 2, payments.getPayment(id)));
        var row = row(id);
        assertThat(row.findings()).contains(HISTORY_VERSION_GAPS);
        assertThat(row.retainedHistoryContiguous()).isFalse();
        assertThat(row.sourceEventCount()).isEqualTo(2);
    }

    @Test
    void consumerOnlyPaymentIsIncludedRatherThanLostThroughSourceInnerJoin() {
        UUID id = UUID.randomUUID();
        consumer.consume(external(id, 0, data(id)));
        var report = reconciliation.reconcile(null, null, 50);
        assertThat(report.totalCandidates()).isEqualTo(1);
        assertThat(report.payments().getFirst().findings()).contains(SOURCE_PAYMENT_MISSING, UNMATCHED_CONSUMER_RECEIPTS);
        assertThat(report.payments().getFirst().latestSourceReplayCandidate()).isFalse();
    }

    @Test
    void receiptOnlyPaymentIsIncludedWithoutAProjection() {
        UUID id = UUID.randomUUID();
        var event = external(id, 0, data(id));
        receipts.insertReceipt(PaymentEventConsumer.CONSUMER_NAME, event, json(event));
        var row = row(id);
        assertThat(row.findings()).contains(SOURCE_PAYMENT_MISSING, UNMATCHED_CONSUMER_RECEIPTS, LATEST_RECEIPT_NOT_PROJECTED);
    }

    @Test
    void sameVersionDifferentEventIdentityIsAConflictAndBlocksReplayCandidate() throws Exception {
        UUID id = create();
        var source = sourceEvent(id, 0);
        consumer.consume(new PaymentEvent(UUID.randomUUID(), source.eventType(), 1, id, 0, source.occurredAt(), source.data()));
        var row = row(id);
        assertThat(row.findings()).contains(SOURCE_RECEIPT_CONFLICT, UNMATCHED_CONSUMER_RECEIPTS, PROJECTION_SNAPSHOT_MISMATCH);
        assertThat(row.conflictingReceipts()).isEqualTo(1);
        assertThat(row.missingReceipts()).isZero();
        assertThat(row.latestSourceReplayCandidate()).isFalse();
    }

    @Test
    void sameIdentityChangedLogicalContentIsAConflict() throws Exception {
        UUID id = create();
        var source = sourceEvent(id, 0);
        var changed = new PaymentResponse(id, "DIFFERENT-PRIVATE-REFERENCE", source.data().amount(), source.data().currency(),
                source.data().status(), source.data().createdAt(), source.data().updatedAt());
        consumer.consume(new PaymentEvent(source.eventId(), source.eventType(), 1, id, 0, source.occurredAt(), changed));
        assertThat(row(id).conflictingReceipts()).isEqualTo(1);
    }

    @Test
    void jsonFieldOrderAndNumericScaleDoNotManufactureReceiptConflicts() throws Exception {
        UUID id = create();
        var event = sourceEvent(id, 0);
        ObjectNode payload = mapper.valueToTree(event);
        ((ObjectNode) payload.path("data")).put("amount", new BigDecimal("10.0000"));
        var reversed = mapper.createObjectNode();
        var names = new ArrayList<String>();
        payload.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        names.forEach(name -> reversed.set(name, payload.get(name)));
        receipts.insertReceipt(PaymentEventConsumer.CONSUMER_NAME, event, reversed.toString());
        receipts.applyProjection(PaymentEventConsumer.CONSUMER_NAME, event);
        assertThat(row(id).findings()).isEmpty();
        assertThat(row(id).matchingReceipts()).isEqualTo(1);
    }

    @Test
    void equalVersionBusinessFieldDriftAndReceiptMismatchAreReported() throws Exception {
        UUID id = create();
        consumeSource(id);
        jdbc.update("UPDATE payment_event_projections SET amount = 11.00 WHERE payment_id = ?", id);
        assertThat(row(id).findings()).contains(PROJECTION_SNAPSHOT_MISMATCH, PROJECTION_RECEIPT_MISMATCH);
    }

    @Test
    void projectionAheadAndUnmatchedConsumerVersionRemainVisible() {
        UUID id = create();
        consumer.consume(external(id, 2, payments.getPayment(id)));
        assertThat(row(id).findings()).contains(PROJECTION_AHEAD, UNMATCHED_CONSUMER_RECEIPTS, SOURCE_RECEIPTS_MISSING);
    }

    @Test
    void matchingReceiptsWithLostProjectionStillNeedASeparateRebuildPlan() throws Exception {
        UUID id = create();
        consumeSource(id);
        jdbc.update("DELETE FROM payment_event_projections WHERE payment_id = ?", id);
        var row = row(id);
        assertThat(row.findings()).containsExactlyInAnyOrder(PROJECTION_MISSING, LATEST_RECEIPT_NOT_PROJECTED);
        assertThat(row.matchingReceipts()).isEqualTo(1);
        assertThat(row.latestSourceReplayCandidate()).isTrue();
        // Ordinary consume deduplicates; report must never pretend it rebuilt this projection.
        assertThat(consumer.consume(sourceEvent(id, 0))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections", Long.class)).isZero();
    }

    @Test
    void sourceBusinessDriftMakesLatestEventAnUnsafeInput() {
        UUID id = create();
        jdbc.update("UPDATE payments SET amount = 99.00 WHERE id = ?", id);
        assertThat(row(id).findings()).contains(CURRENT_SOURCE_SNAPSHOT_MISMATCH);
        assertThat(row(id).latestSourceReplayCandidate()).isFalse();
    }

    @Test
    void incompleteCurrentEventContractIsReportedWithoutExposingItsPayload() {
        var payment = paymentRepository.saveAndFlush(Payment.create("PRIVATE-REFERENCE", BigDecimal.TEN, "INR"));
        var event = external(payment.getId(), 0, payments.getPayment(payment.getId()));
        ObjectNode payload = mapper.valueToTree(event);
        ((ObjectNode) payload.path("data")).remove("createdAt");
        jdbc.update("""
                INSERT INTO payment_outbox (event_id,payment_id,payment_version,event_type,occurred_at,payload)
                VALUES (?, ?, 0, 'PAYMENT_CREATED', ?, ?)
                """, event.eventId(), event.paymentId(), Timestamp.from(event.occurredAt()), payload.toString());
        var row = row(payment.getId());
        assertThat(row.findings()).contains(CURRENT_SOURCE_EVENT_INVALID);
        assertThat(row.latestSourceReplayCandidate()).isFalse();
        assertThat(mapper.valueToTree(row).toString()).doesNotContain("PRIVATE-REFERENCE", "payload", "amount", "currency");
    }

    @Test
    void sourceOutboxAheadOfAuthoritativeVersionIsReported() {
        UUID id = create();
        outbox.save(external(id, 2, payments.getPayment(id)));
        assertThat(row(id).findings()).contains(SOURCE_EVENT_AHEAD, HISTORY_VERSION_GAPS);
        assertThat(row(id).retainedHistoryContiguous()).isFalse();
    }

    @Test
    void pendingAndDeadDeliveryCountsExplainUnconsumedInputs() throws Exception {
        UUID id = create();
        UUID eventId = sourceEvent(id, 0).eventId();
        for (int attempt = 0; attempt < 5; attempt++) outboxFailures.recordFailure(eventId, attempt);
        var row = row(id);
        assertThat(row.deadSourceEvents()).isEqualTo(1);
        assertThat(row.pendingSourceEvents()).isZero();
        assertThat(row.missingReceipts()).isEqualTo(1);
    }

    @Test
    void pagingUsesAllCandidateKindsWithExplicitTotalsAndNoSilentTruncation() {
        UUID first = create();
        UUID second = create();
        UUID orphan = UUID.randomUUID();
        consumer.consume(external(orphan, 0, data(orphan)));
        var seen = new ArrayList<UUID>();
        UUID after = null;
        do {
            var report = reconciliation.reconcile(null, after, 1);
            assertThat(report.totalCandidates()).isEqualTo(3);
            seen.addAll(report.payments().stream().map(PaymentReconciliation::paymentId).toList());
            after = report.nextAfterPaymentId();
            assertThat(report.hasMore()).isEqualTo(after != null);
        } while (after != null);
        assertThat(seen).containsExactlyInAnyOrder(first, second, orphan).doesNotHaveDuplicates();
        assertThat(reconciliation.reconcile(UUID.randomUUID(), null, 50).payments()).isEmpty();
        assertThat(reconciliation.reconcile(first, null, 50).payments()).hasSize(1);
    }

    @Test
    void gapContextIncludesOtherNamespacesWithoutGuessingWhichPaymentMalformedRecordBelongsTo() {
        create();
        var record = new ConsumerRecord<String, String>("payment-events.v1", 0, 0, "PRIVATE-KEY", "PRIVATE-PAYLOAD");
        failures.failed(record, new InvalidPaymentEventException("malformed"));
        UUID failure = jdbc.queryForObject("SELECT failure_id FROM consumer_event_failures", UUID.class);
        recovery.quarantine(new ConsumerRecoveryCommand(UUID.randomUUID(), failure,
                ConsumerFailureRepository.fingerprint(record.key(), record.value()), "operator", "accepted gap"));
        var other = new ConsumerFailureService(new ConsumerFailureRepository(jdbc), "another-group", "retired-source");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                other.failed(new ConsumerRecord<>("retired-topic", 0, 0, "other", "invalid"), new IllegalStateException("transient")));
        var report = reconciliation.reconcile(null, null, 50);
        assertThat(report.totalCandidates()).isEqualTo(1);
        assertThat(report.recoveryGaps().configuredOpenQuarantines()).isEqualTo(1);
        assertThat(report.recoveryGaps().otherScopeOtherOpenFailures()).isEqualTo(1);
        assertThat(mapper.valueToTree(report).toString()).doesNotContain("PRIVATE-KEY", "PRIVATE-PAYLOAD", "PRIVATE-REFERENCE");
    }

    @Test
    void repeatableReadKeepsTotalsVersionsAndComparisonsOnOneReadOnlySnapshot() throws Exception {
        UUID id = create();
        var snapshotRead = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        ProjectionReconciliationRepository spy = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
            snapshotRead.countDown();
            if (!writerCommitted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("writer timed out");
            return result;
        }).when(spy).snapshot(any(), any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reportFuture = executor.submit(() -> reconciliation.reconcile(id, null, 50));
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            payments.authorizePayment(id);
            writerCommitted.countDown();
            var report = reportFuture.get(10, TimeUnit.SECONDS);
            assertThat(report.payments().getFirst().sourceVersion()).isZero();
            assertThat(report.payments().getFirst().sourceEventCount()).isEqualTo(1);
        } finally {
            writerCommitted.countDown();
            doCallRealMethod().when(spy).snapshot(any(), any());
        }
        assertThat(row(id).sourceVersion()).isEqualTo(1);
    }

    @Test
    void reconciliationDoesNotMutateAnyApplicationTable() throws Exception {
        UUID id = create();
        payments.authorizePayment(id);
        payments.capturePayment(id);
        consumeSource(id);
        var before = databaseImage();
        reconciliation.reconcile(null, null, 100);
        assertThat(databaseImage()).isEqualTo(before);
    }

    @Test
    void serviceRejectsUnboundedAndAmbiguousRequests() {
        assertThatThrownBy(() -> reconciliation.reconcile(null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliation.reconcile(null, null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliation.reconcile(UUID.randomUUID(), UUID.randomUUID(), 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID create() {
        return payments.createPayment("reconcile-" + UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-REFERENCE", BigDecimal.TEN, "INR")).payment().id();
    }

    private PaymentReconciliation row(UUID id) { return reconciliation.reconcile(id, null, 50).payments().getFirst(); }

    private PaymentEvent sourceEvent(UUID id, long version) throws Exception {
        return mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id = ? AND payment_version = ?",
                String.class, id, version), PaymentEvent.class);
    }

    private void consumeSource(UUID id) throws Exception {
        for (String json : jdbc.queryForList("SELECT payload FROM payment_outbox WHERE payment_id = ? ORDER BY payment_version",
                String.class, id)) consumer.consume(mapper.readValue(json, PaymentEvent.class));
    }

    private PaymentEvent external(UUID id, long version, PaymentResponse data) {
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.from(data.status()), 1, id, version,
                data.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS), data);
    }

    private PaymentResponse data(UUID id) {
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        return new PaymentResponse(id, "PRIVATE-REFERENCE", BigDecimal.TEN, "INR", PaymentStatus.CREATED, at, at);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private List<String> databaseImage() {
        List<String> image = new ArrayList<>();
        for (String table : List.of("payments", "idempotency_records", "ledger_accounts", "ledger_transactions", "ledger_entries",
                "payment_outbox", "local_payment_event_inbox", "consumed_payment_events", "payment_event_projections",
                "outbox_requeue_audit", "consumer_event_positions", "consumer_event_failures", "consumer_recovery_audit")) {
            image.add(jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM "
                    + table + " t", String.class));
        }
        return image;
    }
}
