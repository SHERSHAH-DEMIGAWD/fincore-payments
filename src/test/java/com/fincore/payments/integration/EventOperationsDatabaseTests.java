package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.OutboxProperties;
import com.fincore.payments.domain.ConsumerFailureErrorCode;
import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.OperationalOutboxEvent;
import com.fincore.payments.dto.RequeueCommand;
import com.fincore.payments.dto.RequeueResult;
import com.fincore.payments.exception.EventOperationConflictException;
import com.fincore.payments.exception.EventOperationNotFoundException;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.exception.PaymentEventConflictException;
import com.fincore.payments.service.ConsumerFailureService;
import com.fincore.payments.service.EventOperationsService;
import com.fincore.payments.service.OutboxFailureRecorder;
import com.fincore.payments.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared assertions against a dedicated real PostgreSQL database; no Kafka connection is needed. */
@SpringBootTest(properties = {"fincore.outbox.enabled=false", "fincore.events.transport=local",
        "fincore.kafka.source-name=primary",
        "fincore.kafka.consumer-group=fincore-payment-projection-v1"})
abstract class EventOperationsDatabaseTests {
    @Autowired EventOperationsService operations;
    @Autowired ConsumerFailureService consumerFailures;
    @Autowired OutboxFailureRecorder publisherFailures;
    @Autowired OutboxProperties outboxProperties;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_failures, consumer_event_positions, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events, local_payment_event_inbox,
                    payment_outbox, ledger_entries, ledger_transactions, ledger_accounts,
                    idempotency_records, payments
                """);
    }

    @Test
    void overviewDistinguishesReadyBlockedBackedOffDeadAndDelivered() {
        UUID ordered = createPayment();
        payments.authorizePayment(ordered);
        UUID blockedBy = firstEvent(ordered);
        dead(blockedBy);
        UUID ready = firstEvent(createPayment());
        UUID backedOff = firstEvent(createPayment());
        jdbc.update("UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE event_id = ?",
                backedOff);
        UUID delivered = firstEvent(createPayment());
        delivered(delivered);

        var overview = operations.overview();

        assertThat(overview.pending()).isEqualTo(3);
        assertThat(overview.dead()).isEqualTo(1);
        assertThat(overview.delivered()).isEqualTo(1);
        assertThat(overview.ready()).isEqualTo(1);
        assertThat(overview.blocked()).isEqualTo(1);
        assertThat(overview.backedOff()).isEqualTo(1);
        assertThat(overview.oldestPendingAt()).isNotNull();
        assertThat(overview.oldestReadyAt()).isEqualTo(state(ready).occurredAt());
        assertThat(operations.events(OutboxStatus.PENDING, 100))
                .filteredOn(event -> event.paymentId().equals(ordered))
                .singleElement().satisfies(event -> assertThat(event.oldestBlockingEventId()).isEqualTo(blockedBy));
    }

    @Test
    void requeuePreservesEnvelopeAndLifetimeAttemptsAndCreatesAudit() {
        UUID event = deadEvent();
        String payload = jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE event_id = ?", String.class, event);

        var result = operations.requeue(command(event));

        assertThat(result.replayed()).isFalse();
        assertThat(result.generation()).isEqualTo(1);
        var state = state(event);
        assertThat(state.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(state.attempts()).isEqualTo(5);
        assertThat(state.cycleAttempts()).isZero();
        assertThat(state.retryGeneration()).isEqualTo(1);
        assertThat(state.lastErrorCode()).isNull();
        assertThat(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE event_id = ?", String.class, event))
                .isEqualTo(payload);
        assertThat(operations.requeues(event, 100)).singleElement().satisfies(audit -> {
            assertThat(audit.previousAttempts()).isEqualTo(5);
            assertThat(audit.previousCycleAttempts()).isEqualTo(5);
            assertThat(audit.requeuedAt()).isEqualTo(result.requeuedAt());
            assertThat(audit.requestId()).isEqualTo(result.requestId());
        });
    }

    @Test
    void sameCommandReplaysItsOriginalResultEvenAfterDelivery() {
        UUID event = deadEvent();
        var command = command(event);
        var original = operations.requeue(command);
        delivered(event);

        var replay = operations.requeue(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.requestId()).isEqualTo(original.requestId());
        assertThat(replay.generation()).isEqualTo(original.generation());
        assertThat(replay.requeuedAt()).isEqualTo(original.requeuedAt());
        assertThat(state(event).status()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(count("outbox_requeue_audit")).isEqualTo(1);
    }

    @Test
    void requestIdCannotBeReusedWithDifferentReasonOrDifferentEvent() {
        UUID first = deadEvent();
        UUID second = deadEvent();
        var command = command(first);
        operations.requeue(command);

        assertThatThrownBy(() -> operations.requeue(new RequeueCommand(command.requestId(), first, 0,
                command.operator(), "different reason"))).isInstanceOf(EventOperationConflictException.class);
        assertThatThrownBy(() -> operations.requeue(new RequeueCommand(command.requestId(), second, 0,
                command.operator(), command.reason()))).isInstanceOf(EventOperationConflictException.class);
        assertThat(state(second).status()).isEqualTo(OutboxStatus.DEAD);
        assertThat(state(second).retryGeneration()).isZero();
        assertThat(count("outbox_requeue_audit")).isEqualTo(1);
    }

    @Test
    void concurrentDifferentCommandsForOneGenerationHaveOneWinner() throws Exception {
        UUID event = deadEvent();
        var commands = List.of(command(event), command(event));

        var accepted = concurrently(commands.stream().<Callable<Boolean>>map(command -> () -> {
            try {
                operations.requeue(command);
                return true;
            } catch (EventOperationConflictException expected) {
                return false;
            }
        }).toList());

        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(count("outbox_requeue_audit")).isEqualTo(1);
        assertThat(state(event).retryGeneration()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalCommandsCreateOneAuditAndOneReplay() throws Exception {
        var command = command(deadEvent());

        List<RequeueResult> results = concurrently(List.of(() -> operations.requeue(command),
                () -> operations.requeue(command)));

        assertThat(results).extracting(RequeueResult::replayed).containsExactlyInAnyOrder(false, true);
        assertThat(results).extracting(RequeueResult::requeuedAt).containsOnly(results.getFirst().requeuedAt());
        assertThat(count("outbox_requeue_audit")).isEqualTo(1);
    }

    @Test
    void auditInsertionFailureRollsBackTheDeliveryMetadataReset() {
        UUID event = deadEvent();
        var before = state(event);
        jdbc.execute("ALTER TABLE outbox_requeue_audit ADD CONSTRAINT milestone6_test_reject_audit CHECK (FALSE) NOT VALID");
        try {
            assertThatThrownBy(() -> operations.requeue(command(event))).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("ALTER TABLE outbox_requeue_audit DROP CONSTRAINT milestone6_test_reject_audit");
        }
        assertThat(state(event)).isEqualTo(before);
        assertThat(count("outbox_requeue_audit")).isZero();
    }

    @Test
    void sourceEnvelopeAndRecoveryAuditRemainImmutable() {
        UUID event = deadEvent();
        operations.requeue(command(event));

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_outbox SET payload = '{}' WHERE event_id = ?", event))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_requeue_audit SET reason = 'changed'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM outbox_requeue_audit"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("outbox_requeue_audit")).isEqualTo(1);
    }

    @Test
    void requeuedEventGetsAFreshBoundedCycleWithMonotonicLifetimeAttempts() {
        UUID event = deadEvent();
        operations.requeue(command(event));

        for (int cycleAttempt = 1; cycleAttempt <= 5; cycleAttempt++) {
            Instant before = databaseNow();
            assertThat(publisherFailures.recordFailure(event, 4 + cycleAttempt)).isTrue();
            Instant after = databaseNow();
            var state = state(event);
            assertThat(state.attempts()).isEqualTo(5 + cycleAttempt);
            assertThat(state.cycleAttempts()).isEqualTo(cycleAttempt);
            assertThat(state.status()).isEqualTo(cycleAttempt == 5 ? OutboxStatus.DEAD : OutboxStatus.PENDING);
            assertThat(state.retryGeneration()).isEqualTo(1);
            long cycleDelay = outboxProperties.retryDelayMs(cycleAttempt);
            assertThat(state.nextAttemptAt()).isBetween(before.plusMillis(cycleDelay), after.plusMillis(cycleDelay));
        }
    }

    @Test
    void staleFailureReportsCannotOverwriteANewCycleOrSuccessfulDelivery() {
        UUID event = deadEvent();
        operations.requeue(command(event));
        var before = state(event);

        assertThat(publisherFailures.recordFailure(event, 4)).isFalse();
        assertThat(state(event)).isEqualTo(before);
        assertThat(publisherFailures.recordFailure(event, 5)).isTrue();
        var after = state(event);
        assertThat(publisherFailures.recordFailure(event, 5)).isFalse();
        assertThat(state(event)).isEqualTo(after);
        delivered(event);
        var delivered = state(event);
        assertThat(publisherFailures.recordFailure(event, 6)).isFalse();
        assertThat(state(event)).isEqualTo(delivered);
    }

    @Test
    void invalidEventStateGenerationAndMissingEventCannotBeRequeued() {
        UUID pending = firstEvent(createPayment());
        UUID delivered = firstEvent(createPayment());
        delivered(delivered);
        UUID dead = deadEvent();

        assertThatThrownBy(() -> operations.requeue(command(pending))).isInstanceOf(EventOperationConflictException.class);
        assertThatThrownBy(() -> operations.requeue(command(delivered))).isInstanceOf(EventOperationConflictException.class);
        assertThatThrownBy(() -> operations.requeue(new RequeueCommand(UUID.randomUUID(), dead, 1,
                "operator", "fixed outage"))).isInstanceOf(EventOperationConflictException.class);
        assertThatThrownBy(() -> operations.requeue(command(UUID.randomUUID())))
                .isInstanceOf(EventOperationNotFoundException.class);
        assertThat(count("outbox_requeue_audit")).isZero();
    }

    @Test
    void databaseRejectsAnUnauditedRetryGenerationAdvanceAtCommit() {
        UUID event = deadEvent();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            jdbc.update("""
                    UPDATE payment_outbox SET status = 'PENDING', cycle_attempts = 0,
                        retry_generation = retry_generation + 1 WHERE event_id = ?
                    """, event);
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(state(event).status()).isEqualTo(OutboxStatus.DEAD);
        assertThat(state(event).retryGeneration()).isZero();
    }

    @Test
    void consumerDiagnosticCommitsIndependentlyOfAnEnclosingRollback() {
        var record = record(1, "private value");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
            consumerFailures.failed(record, new InvalidPaymentEventException("private reason"));
            throw new IllegalStateException("roll back the outer transaction");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(consumerFailures.list(true, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.errorCode()).isEqualTo(ConsumerFailureErrorCode.INVALID_EVENT);
            assertThat(failure.offset()).isEqualTo(1);
            assertThat(failure.sourceName()).isEqualTo("primary");
            assertThat(failure.consumerGroup()).isEqualTo("fincore-payment-projection-v1");
        });
    }

    @Test
    void repeatedConsumerFailuresKeepOneOriginalRecordAndIncrementAttempts() {
        var record = record(2, "private original");
        consumerFailures.failed(record, new InvalidPaymentEventException("bad record"));
        var original = consumerFailures.list(true, 100).getFirst();

        consumerFailures.failed(record, new IllegalStateException("temporary processor failure"));

        assertThat(consumerFailures.list(true, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.failureId()).isEqualTo(original.failureId());
            assertThat(failure.firstFailedAt()).isEqualTo(original.firstFailedAt());
            assertThat(failure.lastFailedAt()).isAfterOrEqualTo(original.lastFailedAt());
            assertThat(failure.attempts()).isEqualTo(2);
            assertThat(failure.errorCode()).isEqualTo(ConsumerFailureErrorCode.CONSUMER_FAILED);
        });
        assertThat(jdbc.queryForObject("SELECT record_value FROM consumer_event_failures", String.class))
                .isEqualTo("private original");
    }

    @Test
    void successfulRetryResolvesDiagnosticsAndALateOldOwnerFailureCannotReopenThem() {
        var record = record(3, "private value");
        consumerFailures.failed(record, new IllegalStateException("temporary failure"));
        var original = consumerFailures.list(true, 100).getFirst();

        consumerFailures.resolved(record);

        assertThat(consumerFailures.list(true, 100)).isEmpty();
        assertThat(consumerFailures.list(false, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.failureId()).isEqualTo(original.failureId());
            assertThat(failure.resolvedAt()).isNotNull();
            assertThat(failure.attempts()).isEqualTo(1);
        });
        consumerFailures.failed(record, new IllegalStateException("old partition owner failed late"));
        assertThat(consumerFailures.list(true, 100)).isEmpty();
        assertThat(consumerFailures.list(false, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.failureId()).isEqualTo(original.failureId());
            assertThat(failure.resolvedAt()).isAfterOrEqualTo(failure.lastFailedAt());
            assertThat(failure.attempts()).isEqualTo(2);
        });
    }

    @Test
    void successBeforeAnyFailureKeepsALateOldOwnerDiagnosticResolved() {
        var record = record(11, "success before the first diagnostic");
        consumerFailures.resolved(record);
        assertThat(count("consumer_event_failures")).isZero();

        consumerFailures.failed(record, new IllegalStateException("late old partition owner failure"));

        assertThat(consumerFailures.list(true, 100)).isEmpty();
        assertThat(consumerFailures.list(false, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.attempts()).isEqualTo(1);
            assertThat(failure.resolvedAt()).isAfterOrEqualTo(failure.lastFailedAt());
        });
        assertThat(count("consumer_event_positions")).isEqualTo(1);
    }

    @Test
    void concurrentSuccessAndFailureAlwaysLeaveTheSourcePositionResolved() throws Exception {
        var record = record(12, "record being handed to a new partition owner");

        concurrently(List.of(() -> {
            consumerFailures.failed(record, new IllegalStateException("old owner failure"));
            return true;
        }, () -> {
            consumerFailures.resolved(record);
            return true;
        }));

        assertThat(consumerFailures.list(true, 100)).isEmpty();
        assertThat(consumerFailures.list(false, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.resolvedAt()).isAfterOrEqualTo(failure.lastFailedAt());
            assertThat(failure.attempts()).isEqualTo(1);
        });
    }

    @Test
    void reusedSourcePositionCannotOverwriteOrResolveDifferentOriginalEvidence() {
        var original = record(4, "first original");
        var conflict = record(4, "different original");
        consumerFailures.failed(original, new InvalidPaymentEventException("bad record"));

        assertThatThrownBy(() -> consumerFailures.failed(conflict, new IllegalStateException("conflict")))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("source position");
        assertThatThrownBy(() -> consumerFailures.resolved(conflict))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("source position");
        assertThat(consumerFailures.list(true, 100)).singleElement()
                .satisfies(failure -> assertThat(failure.attempts()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT record_value FROM consumer_event_failures", String.class))
                .isEqualTo("first original");
    }

    @Test
    void operatorFailureViewsNeverExposePrivateRecordDataOrExceptionMessages() throws Exception {
        var record = new ConsumerRecord<String, String>("payment-events.v1", 2, 5,
                "PRIVATE_KEY", "PRIVATE_PAYLOAD");
        consumerFailures.failed(record, new IllegalStateException("PRIVATE_EXCEPTION"));

        String output = mapper.writeValueAsString(consumerFailures.list(false, 100));

        assertThat(output).doesNotContain("PRIVATE_KEY", "PRIVATE_PAYLOAD", "PRIVATE_EXCEPTION",
                "recordKey", "recordValue", "record_key", "record_value");
        assertThat(output).contains("CONSUMER_FAILED", "payment-events.v1");
    }

    @Test
    void databaseProtectsConsumerFailureOriginalEvidenceAndDeletion() {
        var record = record(6, "private evidence");
        consumerFailures.failed(record, new IllegalStateException("failure"));
        consumerFailures.resolved(record);

        assertThatThrownBy(() -> jdbc.update("UPDATE consumer_event_failures SET record_value = 'replacement'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE consumer_event_failures SET consumer_group = 'replacement'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM consumer_event_failures"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE consumer_event_positions SET processed_at = NULL"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE consumer_event_positions SET wire_fingerprint = repeat('0', 64)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM consumer_event_positions"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("consumer_event_failures")).isEqualTo(1);
    }

    @Test
    void errorCodesAreSanitizedAndTombstoneEvidenceRetainsNulls() {
        consumerFailures.failed(record(7, "invalid"), new InvalidPaymentEventException("private invalid"));
        consumerFailures.failed(record(8, "conflicting"), new PaymentEventConflictException());
        consumerFailures.failed(new ConsumerRecord<>("payment-events.v1", 0, 9, null, null),
                new IllegalStateException("private failure"));

        assertThat(consumerFailures.list(true, 100)).extracting(failure -> failure.errorCode())
                .containsExactlyInAnyOrder(ConsumerFailureErrorCode.INVALID_EVENT,
                        ConsumerFailureErrorCode.EVENT_CONFLICT, ConsumerFailureErrorCode.CONSUMER_FAILED);
        assertThat(jdbc.queryForObject("""
                SELECT record_key IS NULL AND record_value IS NULL
                FROM consumer_event_failures WHERE record_offset = 9
                """, Boolean.class)).isTrue();
    }

    @Test
    void concurrentFailuresForOnePositionKeepOneEvidenceRowAndBothAttempts() throws Exception {
        var record = record(10, "same private value");

        concurrently(List.of(() -> {
            consumerFailures.failed(record, new IllegalStateException("failure one"));
            return true;
        }, () -> {
            consumerFailures.failed(record, new IllegalStateException("failure two"));
            return true;
        }));

        assertThat(consumerFailures.list(true, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.attempts()).isEqualTo(2);
            assertThat(failure.lastFailedAt()).isAfterOrEqualTo(failure.firstFailedAt());
        });
    }

    private UUID createPayment() {
        String identity = UUID.randomUUID().toString();
        return payments.createPayment(identity,
                new CreatePaymentRequest(identity, new BigDecimal("10.00"), "INR")).payment().id();
    }

    private UUID firstEvent(UUID paymentId) {
        return jdbc.queryForObject("SELECT event_id FROM payment_outbox WHERE payment_id = ? AND payment_version = 0",
                UUID.class, paymentId);
    }

    private UUID deadEvent() {
        UUID eventId = firstEvent(createPayment());
        dead(eventId);
        return eventId;
    }

    private void dead(UUID eventId) {
        jdbc.update("""
                UPDATE payment_outbox SET status = 'DEAD', attempts = 5, cycle_attempts = 5,
                    last_error_code = 'PUBLISH_FAILED' WHERE event_id = ?
                """, eventId);
    }

    private void delivered(UUID eventId) {
        jdbc.update("""
                UPDATE payment_outbox SET status = 'DELIVERED', attempts = attempts + 1,
                    cycle_attempts = cycle_attempts + 1, delivered_at = CURRENT_TIMESTAMP,
                    last_error_code = NULL WHERE event_id = ?
                """, eventId);
    }

    private OperationalOutboxEvent state(UUID eventId) {
        return operations.events(null, 100).stream().filter(event -> event.eventId().equals(eventId))
                .findFirst().orElseThrow();
    }

    private RequeueCommand command(UUID eventId) {
        return new RequeueCommand(UUID.randomUUID(), eventId, 0, "test-operator", "broker outage repaired");
    }

    private ConsumerRecord<String, String> record(long offset, String value) {
        return new ConsumerRecord<>("payment-events.v1", 0, offset, "private key", value);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private <T> List<T> concurrently(List<Callable<T>> actions) throws Exception {
        var barrier = new CyclicBarrier(actions.size());
        try (var executor = Executors.newFixedThreadPool(actions.size())) {
            var futures = actions.stream().map(action -> executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return action.call();
            })).toList();
            var results = new java.util.ArrayList<T>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }
}
