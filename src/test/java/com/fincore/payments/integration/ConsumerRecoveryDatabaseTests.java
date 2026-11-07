package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.ConsumerRecoveryCommand;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.exception.ConsumerRecoveryConflictException;
import com.fincore.payments.exception.ConsumerRecoveryNotFoundException;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.repository.ConsumerFailureRepository;
import com.fincore.payments.service.ConsumerFailureService;
import com.fincore.payments.service.ConsumerRecordProcessor;
import com.fincore.payments.service.ConsumerRecoveryService;
import com.fincore.payments.service.PaymentEventConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"fincore.events.transport=local", "fincore.outbox.enabled=false"})
abstract class ConsumerRecoveryDatabaseTests {
    @Autowired ConsumerRecoveryService recovery;
    @Autowired ConsumerFailureService failures;
    @Autowired ConsumerRecordProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;

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
    void quarantinePreservesEvidenceAndLeavesAnExplicitUnprocessedGap() {
        var record = malformed(0);
        var command = observe(record);
        var before = jdbc.queryForMap("SELECT * FROM consumer_event_failures");

        var result = recovery.quarantine(command);
        processor.process(record);

        assertThat(result.action()).isEqualTo("QUARANTINE");
        assertThat(result.replayed()).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM consumer_event_failures")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT processed_at FROM consumer_event_positions", Instant.class)).isNull();
        assertThat(recovery.gaps().status()).isEqualTo("DEGRADED");
        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
        assertThat(recovery.gaps().otherOpenFailures()).isZero();
        assertThat(count("consumed_payment_events")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("ledger_transactions")).isZero();
        assertThat(mapper.valueToTree(recovery.quarantines(true, 50)).toString()).doesNotContain("PRIVATE-PAYLOAD");
    }

    @Test
    void exactQuarantineReplayReturnsOriginalDecisionAndChangedIntentConflicts() {
        var command = observe(malformed(0));
        var first = recovery.quarantine(command);
        var replay = recovery.quarantine(command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.decidedAt()).isEqualTo(first.decidedAt());
        assertThatThrownBy(() -> recovery.quarantine(new ConsumerRecoveryCommand(command.requestId(), command.failureId(),
                command.expectedFingerprint(), command.operator(), "changed reason")))
                .isInstanceOf(ConsumerRecoveryConflictException.class);
        assertThatThrownBy(() -> recovery.replay(command)).isInstanceOf(ConsumerRecoveryConflictException.class);
        assertThat(count("consumer_recovery_audit")).isEqualTo(1);
    }

    @Test
    void invalidReplayRollsBackAndDoesNotCloseTheGap() {
        var command = observe(malformed(0));
        recovery.quarantine(command);

        assertThatThrownBy(() -> recovery.replay(newRequest(command))).isInstanceOf(InvalidPaymentEventException.class);

        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
        assertThat(count("consumer_recovery_audit")).isEqualTo(1);
        assertThat(count("consumed_payment_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM consumer_event_failures", Instant.class)).isNull();
    }

    @Test
    void originalReplayAfterACompatibleFixAtomicallyClosesGapAndIsIdempotent() throws Exception {
        var record = valid(0);
        // Simulates evidence retained by an earlier decoder which rejected this now-supported record.
        var quarantine = observe(record);
        recovery.quarantine(quarantine);
        var replayCommand = newRequest(quarantine);

        var result = recovery.replay(replayCommand);

        assertThat(result.action()).isEqualTo("REPLAY");
        assertThat(recovery.gaps().status()).isEqualTo("NO_KNOWN_GAPS");
        assertThat(recovery.quarantines(true, 50)).isEmpty();
        assertThat(recovery.quarantines(false, 50)).hasSize(1);
        assertThat(recovery.quarantines(false, 50).getFirst().replayRequestId()).isEqualTo(replayCommand.requestId());
        assertThat(count("consumed_payment_events")).isEqualTo(1);
        assertThat(count("payment_event_projections")).isEqualTo(1);
        assertThat(count("payments")).isZero();
        assertThat(count("ledger_transactions")).isZero();
        assertThat(recovery.replay(replayCommand).replayed()).isTrue();
        assertThat(recovery.quarantine(quarantine).replayed()).isTrue();
        processor.process(record);
        failures.failed(record, new InvalidPaymentEventException("late old-owner failure"));
        assertThat(recovery.gaps().status()).isEqualTo("NO_KNOWN_GAPS");
        assertThat(count("consumer_recovery_audit")).isEqualTo(2);
        assertThat(count("consumed_payment_events")).isEqualTo(1);
    }

    @Test
    void staleFingerprintAndTransientFailureCannotBeQuarantined() {
        var command = observe(malformed(0));
        assertThatThrownBy(() -> recovery.quarantine(new ConsumerRecoveryCommand(command.requestId(), command.failureId(),
                "0".repeat(64), command.operator(), command.reason())))
                .isInstanceOf(ConsumerRecoveryConflictException.class);
        failures.failed(malformed(0), new IllegalStateException("transient database failure"));
        assertThatThrownBy(() -> recovery.quarantine(command)).isInstanceOf(ConsumerRecoveryConflictException.class);
        assertThat(recovery.gaps().otherOpenFailures()).isEqualTo(1);
        assertThat(count("consumer_recovery_audit")).isZero();
    }

    @Test
    void successfullyProcessedRecordRejectsStaleQuarantineIntent() throws Exception {
        var record = valid(0);
        var command = observe(record);
        processor.process(record);
        assertThatThrownBy(() -> recovery.quarantine(command)).isInstanceOf(ConsumerRecoveryConflictException.class);
        assertThat(count("consumer_recovery_audit")).isZero();
    }

    @Test
    void changedPhysicalRecordCannotReuseQuarantinedPosition() {
        var command = observe(malformed(0));
        recovery.quarantine(command);
        assertThatThrownBy(() -> processor.process(new ConsumerRecord<>("payment-events.v1", 0, 0, "key", "changed")))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("source position conflicts");
        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
    }

    @Test
    void sourceNamespaceAndTopicScopeAreEnforced() {
        var record = new ConsumerRecord<String, String>("another-topic", 0, 0, "key", "invalid");
        var command = observe(record);
        assertThatThrownBy(() -> recovery.quarantine(command)).isInstanceOf(ConsumerRecoveryNotFoundException.class);
        jdbc.update("UPDATE consumer_event_failures SET error_code = 'EVENT_CONFLICT'");
        assertThat(recovery.gaps().otherOpenFailures()).isZero();
    }

    @Test
    void databaseRejectsAuditMutationAndUnauditedResolution() {
        var command = observe(malformed(0));
        recovery.quarantine(command);
        assertThatThrownBy(() -> jdbc.update("UPDATE consumer_recovery_audit SET reason = 'rewrite'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM consumer_recovery_audit"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                jdbc.update("UPDATE consumer_event_positions SET processed_at = clock_timestamp()")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> failures.resolved(malformed(0))).isInstanceOf(RuntimeException.class);
        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
    }

    @Test
    void databaseRejectsReplayAuditWithoutSuccessfulProcessing() {
        var command = observe(malformed(0));
        recovery.quarantine(command);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.update("""
                INSERT INTO consumer_recovery_audit (request_id, failure_id, action, fingerprint, operator, reason)
                VALUES (?, ?, 'REPLAY', ?, 'operator', 'missing processing')
                """, UUID.randomUUID(), command.failureId(), command.expectedFingerprint())))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("consumer_recovery_audit")).isEqualTo(1);
    }

    @Test
    void concurrentDistinctQuarantineCommandsHaveOneWinner() throws Exception {
        var command = observe(malformed(0));
        var barrier = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                barrier.await();
                try { recovery.quarantine(newRequest(command)); return true; }
                catch (ConsumerRecoveryConflictException conflict) { return false; }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            barrier.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("consumer_recovery_audit")).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalCommandsProduceOneAuditAndOneReplay() throws Exception {
        var command = observe(malformed(0));
        var barrier = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> { barrier.await(); return recovery.quarantine(command).replayed(); };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            barrier.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("consumer_recovery_audit")).isEqualTo(1);
    }

    @Test
    void replayOfOlderSnapshotDoesNotOverwriteNewerProjection() throws Exception {
        var record = valid(0);
        var command = observe(record);
        recovery.quarantine(command);
        var event = mapper.readValue(record.value(), PaymentEvent.class);
        var newer = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_AUTHORIZED, 1,
                event.paymentId(), 1, event.occurredAt(), new PaymentResponse(event.paymentId(), "PRIVATE-PAYLOAD",
                new BigDecimal("10.00"), "INR", PaymentStatus.AUTHORIZED, event.occurredAt(), event.occurredAt()));
        processor.process(new ConsumerRecord<>("payment-events.v1", 0, 1, record.key(), mapper.writeValueAsString(newer)));
        recovery.replay(newRequest(command));
        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections", Long.class)).isEqualTo(1);
        assertThat(count("consumed_payment_events")).isEqualTo(2);
        assertThat(recovery.gaps().openQuarantines()).isZero();
    }

    @Test
    void missingQuarantineCannotBeReplayedAndEmptyHistoryDoesNotClaimCompleteness() throws Exception {
        assertThat(recovery.gaps().status()).isEqualTo("NO_KNOWN_GAPS");
        var command = observe(valid(0));
        assertThatThrownBy(() -> recovery.replay(command)).isInstanceOf(ConsumerRecoveryConflictException.class);
        assertThatThrownBy(() -> recovery.quarantine(new ConsumerRecoveryCommand(UUID.randomUUID(), UUID.randomUUID(),
                command.expectedFingerprint(), "operator", "missing"))).isInstanceOf(ConsumerRecoveryNotFoundException.class);
    }

    @Test
    void replayAuditFailureRollsBackReceiptProjectionAndResolution() throws Exception {
        var command = observe(valid(0));
        recovery.quarantine(command);
        jdbc.execute("""
                CREATE FUNCTION test_reject_replay_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated audit failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_replay_audit BEFORE INSERT ON consumer_recovery_audit
                FOR EACH ROW WHEN (NEW.action = 'REPLAY') EXECUTE FUNCTION test_reject_replay_audit()
                """);
        try {
            assertThatThrownBy(() -> recovery.replay(newRequest(command))).isInstanceOf(RuntimeException.class);
            assertThat(count("consumed_payment_events")).isZero();
            assertThat(count("payment_event_projections")).isZero();
            assertThat(jdbc.queryForObject("SELECT processed_at FROM consumer_event_positions", Instant.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT resolved_at FROM consumer_event_failures", Instant.class)).isNull();
            assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_replay_audit ON consumer_recovery_audit");
            jdbc.execute("DROP FUNCTION test_reject_replay_audit()");
        }
    }

    @Test
    void liveResolutionFailureRollsBackTheProjectionAndReceipt() throws Exception {
        var record = valid(0);
        observe(record);
        jdbc.execute("""
                CREATE FUNCTION test_reject_position_success() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated resolution failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_position_success BEFORE UPDATE ON consumer_event_positions
                FOR EACH ROW WHEN (NEW.processed_at IS NOT NULL) EXECUTE FUNCTION test_reject_position_success()
                """);
        try {
            assertThatThrownBy(() -> processor.process(record)).isInstanceOf(RuntimeException.class);
            assertThat(count("consumed_payment_events")).isZero();
            assertThat(count("payment_event_projections")).isZero();
            assertThat(jdbc.queryForObject("SELECT processed_at FROM consumer_event_positions", Instant.class)).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_position_success ON consumer_event_positions");
            jdbc.execute("DROP FUNCTION test_reject_position_success()");
        }
    }

    @Test
    void liveProcessingAndQuarantineRaceCannotCommitConflictingOutcomes() throws Exception {
        var record = valid(0);
        var command = observe(record);
        var barrier = new CountDownLatch(1);
        boolean quarantined;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var processing = executor.submit(() -> { barrier.await(); processor.process(record); return true; });
            var disposition = executor.submit(() -> {
                barrier.await();
                try { recovery.quarantine(command); return true; }
                catch (ConsumerRecoveryConflictException conflict) { return false; }
            });
            barrier.countDown();
            assertThat(processing.get(10, TimeUnit.SECONDS)).isTrue();
            quarantined = disposition.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("consumed_payment_events")).isEqualTo(quarantined ? 0 : 1);
        assertThat(recovery.gaps().openQuarantines()).isEqualTo(quarantined ? 1 : 0);
        assertThat(count("consumer_recovery_audit")).isEqualTo(quarantined ? 1 : 0);
    }

    @Test
    void requestIdCannotBeReusedForAnotherFailure() {
        var first = observe(malformed(0));
        recovery.quarantine(first);
        var second = observe(malformed(1));
        var reused = new ConsumerRecoveryCommand(first.requestId(), second.failureId(), second.expectedFingerprint(),
                second.operator(), second.reason());
        assertThatThrownBy(() -> recovery.quarantine(reused)).isInstanceOf(ConsumerRecoveryConflictException.class);
        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
        assertThat(recovery.gaps().otherOpenFailures()).isEqualTo(1);
    }

    private ConsumerRecoveryCommand observe(ConsumerRecord<String, String> record) {
        failures.failed(record, new InvalidPaymentEventException("simulated earlier decoder rejection"));
        UUID failure = jdbc.queryForObject("SELECT failure_id FROM consumer_event_failures WHERE topic = ? AND record_offset = ?",
                UUID.class, record.topic(), record.offset());
        return new ConsumerRecoveryCommand(UUID.randomUUID(), failure,
                ConsumerFailureRepository.fingerprint(record.key(), record.value()), "local-operator", "reviewed original failure");
    }

    private ConsumerRecoveryCommand newRequest(ConsumerRecoveryCommand command) {
        return new ConsumerRecoveryCommand(UUID.randomUUID(), command.failureId(), command.expectedFingerprint(),
                command.operator(), command.reason());
    }

    private ConsumerRecord<String, String> malformed(long offset) {
        return new ConsumerRecord<>("payment-events.v1", 0, offset, "key", "PRIVATE-PAYLOAD malformed");
    }

    private ConsumerRecord<String, String> valid(long offset) throws Exception {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        var data = new PaymentResponse(id, "PRIVATE-PAYLOAD", new BigDecimal("10.00"), "INR", PaymentStatus.CREATED, at, at);
        var event = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1, id, 0, at, data);
        return new ConsumerRecord<>("payment-events.v1", 0, offset, id.toString(), mapper.writeValueAsString(event));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
}
