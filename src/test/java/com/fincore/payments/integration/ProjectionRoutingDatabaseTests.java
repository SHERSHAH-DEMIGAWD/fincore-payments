package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.dto.ProjectionPromotionCommand;
import com.fincore.payments.dto.ProjectionRollbackCommand;
import com.fincore.payments.dto.ProjectionReplacementCommand;
import com.fincore.payments.dto.ProjectionRetirementCommand;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.exception.PaymentEventConflictException;
import com.fincore.payments.exception.ProjectionRoutingConflictException;
import com.fincore.payments.exception.ProjectionRoutingNotFoundException;
import com.fincore.payments.exception.ShadowRebuildConflictException;
import com.fincore.payments.repository.ConsumerFailureRepository;
import com.fincore.payments.repository.PaymentEventConsumerRepository;
import com.fincore.payments.repository.ProjectionRebuildRepository;
import com.fincore.payments.repository.ProjectionReconciliationRepository;
import com.fincore.payments.repository.ProjectionRoutingRepository;
import com.fincore.payments.service.ConsumerFailureService;
import com.fincore.payments.service.PaymentEventConsumer;
import com.fincore.payments.service.PaymentService;
import com.fincore.payments.service.ProjectionRebuildService;
import com.fincore.payments.service.ProjectionRoutingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL checks for routing, cutover fences and atomic warm-target delivery. */
@SpringBootTest(properties = {"fincore.events.transport=local", "fincore.outbox.enabled=false"})
abstract class ProjectionRoutingDatabaseTests {
    private static final String BASELINE = PaymentEventConsumer.CONSUMER_NAME;
    @Autowired ProjectionRoutingService routing;
    @Autowired ProjectionRoutingRepository routeRepository;
    @Autowired ProjectionRebuildService rebuild;
    @Autowired ProjectionRebuildRepository rebuildRepository;
    @Autowired ProjectionReconciliationRepository reconciliation;
    @Autowired PaymentService payments;
    @Autowired PaymentEventConsumer consumer;
    @Autowired PaymentEventConsumerRepository receipts;
    @Autowired ConsumerFailureService failures;
    @Autowired ConsumerFailureRepository failureRepository;
    @Autowired KafkaEventProperties kafka;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @Autowired com.fincore.payments.config.ProjectionOperationsProperties operatingProperties;

    @BeforeEach
    void clearDisposableDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit,
                    projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs,
                    consumer_recovery_audit, consumer_event_failures, consumer_event_positions, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events, local_payment_event_inbox, payment_outbox,
                    ledger_entries, ledger_transactions, ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void absentRouteUsesBaselineAtRevisionZero() {
        assertThat(routing.status().revision()).isZero();
        assertThat(routing.status().activeConsumer()).isEqualTo(BASELINE);
        assertThat(routing.status().shadowRunId()).isNull();
        assertThat(count("projection_routes")).isZero();
    }

    @Test
    void liveConsumerRejectsAnInheritedSnapshotThatPredatesEnrollment() throws Exception {
        UUID payment=createAndConsume();
        UUID run=build();
        var old=new TransactionTemplate(transactions);
        old.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor=Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> old.executeWithoutResult(tx -> {
                assertThat(jdbc.queryForObject("SELECT routing_enrolled FROM projection_rebuild_runs WHERE run_id=?",
                        Boolean.class,run)).isFalse();
                try {
                    var next=executor.submit(() -> {
                        routing.promote(promotion(run,0),"alice");
                        payments.authorizePayment(payment);
                        return sourceEvent(payment,1);
                    }).get(10,TimeUnit.SECONDS);
                    consumer.consume(next);
                } catch (RuntimeException failure) { throw failure; }
                catch (Exception failure) { throw new AssertionError(failure); }
            // @Repository may translate IllegalStateException; the consumer contract is a
            // refusal before writes, independent of Spring's outer exception wrapper.
            })).isInstanceOf(RuntimeException.class).hasMessageContaining("Live event processing requires READ COMMITTED isolation.");
        }
        assertVersions(payment,run,0);
        consumer.consume(sourceEvent(payment,1));
        assertVersions(payment,run,1);
    }

    @Test
    void completedCurrentEquivalentBuildCanBePromotedAndReadThroughItsIdentity() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        var result = routing.promote(promotion(run, 0), "alice");
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.actor()).isEqualTo("alice");
        assertThat(result.replayed()).isFalse();
        assertThat(routing.status().activeConsumer()).isEqualTo(shadow(run));
        assertThat(routing.status().shadowRunId()).isEqualTo(run);
        assertThat(jdbc.queryForObject("SELECT routing_enrolled FROM projection_rebuild_runs WHERE run_id=?",
                Boolean.class, run)).isTrue();
        jdbc.update("UPDATE payment_event_projections SET projected_at=projected_at-INTERVAL '1 hour' WHERE consumer_name=?", BASELINE);
        assertThat(consumer.getProjection(payment)).isEqualTo(receipts.findProjection(shadow(run), payment).orElseThrow());
        assertThat(count("ledger_transactions")).isZero();
    }

    @Test
    void incompleteBuildCannotChangeTheRouteOrAudit() throws Exception {
        createAndConsume();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "builder", "unfinished manifest");
        assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
        assertNoRoute();
    }

    @Test
    void sourceChangeAfterSuccessfulReviewRequiresNewCatchUpBeforePromotion() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        assertThat(rebuild.validate(run).status()).isEqualTo("MATCHES_CURRENT_SOURCE");
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment, 1));
        assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
        assertNoRoute();
        rebuild.catchUp(run, 0, UUID.randomUUID(), "builder", "new committed version");
        rebuild.step(run, 100);
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(), run, 1, 0, "fresh generation"), "alice");
        assertThat(routing.status().revision()).isEqualTo(1);
    }

    @Test
    void currentShadowCannotBePromotedWhileBaselineIsBehind() throws Exception {
        UUID payment = createAndConsume();
        payments.authorizePayment(payment);
        UUID run = build();
        assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
        assertNoRoute();
        consumer.consume(sourceEvent(payment, 1));
        routing.promote(promotion(run, 0), "alice");
    }

    @Test
    void configuredOpenFailureBlocksPromotionWithoutResolvingTheEvidence() throws Exception {
        createAndConsume();
        UUID run = build();
        failures.failed(badRecord(3), new InvalidPaymentEventException("original invalid record"));
        assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
        assertNoRoute();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_event_failures WHERE resolved_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void otherNamespaceOpenFailureAlsoBlocksPromotion() throws Exception {
        createAndConsume();
        UUID run = build();
        var other = new ConsumerFailureService(failureRepository, kafka.consumerGroup(), "secondary");
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                other.failed(badRecord(4), new InvalidPaymentEventException("other namespace invalid record")));
        assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
        assertNoRoute();
    }

    @Test
    void externalBaselinePaymentCannotDisappearDuringFirstPromotion() throws Exception {
        createAndConsume();
        UUID run = build();
        consumer.consume(externalEvent());
        assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
        assertNoRoute();
    }

    @Test
    void staleExpectedRouteRevisionCannotOverwriteAnOperatorDecision() throws Exception {
        createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        assertConflict(() -> routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(), 0, "stale review"), "bob"));
        assertThat(routing.status().revision()).isEqualTo(1);
        assertThat(count("projection_route_audit")).isEqualTo(1);
    }

    @Test
    void exactPromotionRequestReplaysItsOriginalResultAfterRollback() throws Exception {
        createAndConsume();
        UUID run = build();
        var command = promotion(run, 0);
        var original = routing.promote(command, "alice");
        routing.rollback(rollback(1), "bob");
        var replay = routing.promote(command, "alice");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.revision()).isEqualTo(original.revision());
        assertThat(replay.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(routing.status().revision()).isEqualTo(2);
        assertThat(routing.status().activeConsumer()).isEqualTo(BASELINE);
        assertThat(count("projection_route_audit")).isEqualTo(2);
    }

    @Test
    void reusedRequestCannotChangeActorReasonRunGenerationOrAction() throws Exception {
        createAndConsume();
        UUID run = build();
        var command = promotion(run, 0);
        routing.promote(command, "alice");
        assertConflict(() -> routing.promote(command, "bob"));
        assertConflict(() -> routing.promote(new ProjectionPromotionCommand(command.requestId(), run, 0, 0, "changed"), "alice"));
        assertConflict(() -> routing.promote(new ProjectionPromotionCommand(command.requestId(), UUID.randomUUID(), 0, 0, command.reason()), "alice"));
        assertConflict(() -> routing.promote(new ProjectionPromotionCommand(command.requestId(), run, 1, 0, command.reason()), "alice"));
        assertConflict(() -> routing.rollback(new ProjectionRollbackCommand(command.requestId(), 0, command.reason()), "alice"));
        assertThat(count("projection_route_audit")).isEqualTo(1);
    }

    @Test
    void rollbackAndRepromotionUseTheSameMaintainedShadow() throws Exception {
        createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        routing.rollback(rollback(1), "bob");
        routing.promote(promotion(run, 2), "alice");
        assertThat(routing.status().revision()).isEqualTo(3);
        assertThat(routing.status().shadowRunId()).isEqualTo(run);
        assertThat(jdbc.queryForList("SELECT action FROM projection_route_audit ORDER BY revision", String.class))
                .containsExactly("PROMOTE", "ROLLBACK", "PROMOTE");
    }

    @Test
    void rollbackBeforeEnrollmentIsAConflict() {
        assertConflict(() -> routing.rollback(rollback(0), "alice"));
        assertNoRoute();
    }

    @Test
    void rollbackDoesNotPermitImplicitReplacementThroughPromotion() throws Exception {
        createAndConsume();
        UUID first = build();
        UUID second = build();
        routing.promote(promotion(first, 0), "alice");
        routing.rollback(rollback(1), "bob");
        assertConflict(() -> routing.promote(promotion(second, 2), "alice"));
        assertThat(routing.status().shadowRunId()).isEqualTo(first);
        assertThat(count("projection_route_audit")).isEqualTo(2);
    }

    @Test
    void futureEventsMaintainBothViewsWithoutRepeatingFinancialWrites() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment, 1));
        payments.capturePayment(payment);
        var captured = sourceEvent(payment, 2);
        assertThat(consumer.consume(captured)).isTrue();
        assertThat(consumer.consume(captured)).isFalse();
        assertVersions(payment, run, 2);
        assertThat(count("ledger_transactions")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(count("consumed_payment_events")).isEqualTo(6);
        routing.rollback(rollback(1), "bob");
        assertThat(consumer.getProjection(payment).paymentVersion()).isEqualTo(2);
    }

    @Test
    void shadowContinuesReceivingEventsWhileBaselineIsActiveAfterRollback() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        routing.rollback(rollback(1), "bob");
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment, 1));
        assertVersions(payment, run, 1);
        routing.promote(promotion(run, 2), "alice");
        assertThat(consumer.getProjection(payment).paymentVersion()).isEqualTo(1);
    }

    @Test
    void duplicateBaselineHistoryStillReachesTheEnrolledShadowWithoutRegressingIt() throws Exception {
        UUID payment = createAndConsume();
        var original = sourceEvent(payment, 0);
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment, 1));
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        assertThat(receiptCount(shadow(run))).isEqualTo(1);
        assertThat(consumer.consume(original)).isFalse();
        assertThat(receiptCount(shadow(run))).isEqualTo(2);
        assertVersions(payment, run, 1);
    }

    @Test
    void externalEventsAfterEnrollmentAreAvailableOnBothRoutes() throws Exception {
        createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        var external = externalEvent();
        consumer.consume(external);
        assertVersions(external.paymentId(), run, 0);
        routing.rollback(rollback(1), "bob");
        assertThat(consumer.getProjection(external.paymentId()).lastEventId()).isEqualTo(external.eventId());
    }

    @Test
    void shadowReceiptConflictRollsBackTheBaselineReceiptAndProjectionTogether() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        payments.authorizePayment(payment);
        var official = sourceEvent(payment, 1);
        var collision = new PaymentEvent(UUID.randomUUID(), official.eventType(), official.schemaVersion(), payment,
                official.paymentVersion(), official.occurredAt(), official.data());
        receipts.insertReceipt(shadow(run), collision, mapper.writeValueAsString(collision));
        assertThatThrownBy(() -> consumer.consume(official)).isInstanceOf(PaymentEventConflictException.class);
        assertVersions(payment, run, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE consumer_name=? AND event_id=?",
                Long.class, BASELINE, official.eventId())).isZero();
    }

    @Test
    void duplicateLiveEventFailsClosedWhenMaintainedShadowHasDrifted() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        jdbc.update("UPDATE payment_event_projections SET merchant_reference='tampered view' WHERE consumer_name=?", shadow(run));
        assertThatThrownBy(() -> consumer.consume(sourceEvent(payment, 0))).isInstanceOf(PaymentEventConflictException.class);
        assertThat(receipts.findProjection(BASELINE, payment).orElseThrow().merchantReference()).isEqualTo("PRIVATE-ROUTING-REFERENCE");
        assertThat(receipts.findProjection(shadow(run), payment).orElseThrow().merchantReference()).isEqualTo("tampered view");
        assertThat(count("consumed_payment_events")).isEqualTo(2);
    }

    @Test
    void duplicateLiveEventFailsClosedWhenMaintainedShadowProjectionWasLost() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        jdbc.update("DELETE FROM payment_event_projections WHERE consumer_name=?", shadow(run));
        assertThatThrownBy(() -> consumer.consume(sourceEvent(payment, 0))).isInstanceOf(PaymentEventConflictException.class);
        assertThat(receipts.findProjection(shadow(run), payment)).isEmpty();
        assertThat(receipts.findProjection(BASELINE, payment)).isPresent();
        assertThat(count("consumed_payment_events")).isEqualTo(2);
    }

    @Test
    void aNewSourceVersionBlocksRollbackUntilBothWarmViewsCatchUp() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        payments.authorizePayment(payment);
        assertConflict(() -> routing.rollback(rollback(1), "bob"));
        assertThat(routing.status().revision()).isEqualTo(1);
        consumer.consume(sourceEvent(payment, 1));
        routing.rollback(rollback(1), "bob");
        assertThat(routing.status().revision()).isEqualTo(2);
    }

    @Test
    void activeProjectionDriftBlocksRollbackRatherThanSilentlyChangingReturnedFacts() throws Exception {
        createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        jdbc.update("UPDATE payment_event_projections SET merchant_reference='drifted shadow' WHERE consumer_name=?", shadow(run));
        assertConflict(() -> routing.rollback(rollback(1), "bob"));
        assertThat(routing.status().activeConsumer()).isEqualTo(shadow(run));
    }

    @Test
    void enrolledRebuildCannotResumePlanningOrCheckpointMutation() throws Exception {
        createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        assertThatThrownBy(() -> rebuild.catchUp(run, 0, UUID.randomUUID(), "builder", "cannot alter enrolled evidence"))
                .isInstanceOf(ShadowRebuildConflictException.class);
        assertThatThrownBy(() -> rebuild.step(run, 100)).isInstanceOf(ShadowRebuildConflictException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_runs SET current_generation=1 WHERE run_id=?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rebuild.status(run).generation()).isZero();
    }

    @Test
    void enrolledFlagCannotCommitWithoutItsRoute() throws Exception {
        createAndConsume();
        UUID run = build();
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_runs SET routing_enrolled=true WHERE run_id=?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT routing_enrolled FROM projection_rebuild_runs WHERE run_id=?", Boolean.class, run)).isFalse();
    }

    @Test
    void routeAndAuditMustCommitTogetherAndRequireEnrollmentFlag() throws Exception {
        createAndConsume();
        UUID run = build();
        UUID request = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO projection_routes(route_key,revision,shadow_run_id,active_consumer,last_request_id)
                VALUES (true,1,?,?,?)
                """, run, shadow(run), request)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            insertAudit(request, run, "PROMOTE", 0, shadow(run));
            jdbc.update("""
                    INSERT INTO projection_routes(route_key,revision,shadow_run_id,active_consumer,last_request_id)
                    VALUES (true,1,?,?,?)
                    """, run, shadow(run), request);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertNoRoute();
    }

    @Test
    void auditHistoryCannotBeUpdatedDeletedOrExtendedWithoutAdvancingTheRoute() throws Exception {
        createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_route_audit SET actor='forged'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projection_route_audit"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(UUID.randomUUID(), run, "ROLLBACK", 1, BASELINE))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("projection_route_audit")).isEqualTo(1);
    }

    @Test
    void enrolledIdentityAndRouteRevisionsCannotBeRewrittenOrRemoved() throws Exception {
        createAndConsume();
        UUID run = build();
        UUID another = build();
        routing.promote(promotion(run, 0), "alice");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projection_routes")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_routes SET revision=revision+2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_routes SET shadow_run_id=?", another))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(routing.status().shadowRunId()).isEqualTo(run);
    }

    @Test
    void busySharedConsumerFenceCausesBoundedConflictAndCanBeRetried() throws Exception {
        createAndConsume();
        UUID run = build();
        withHeldTransaction(() -> receipts.lockRouting(), () -> {
            assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
            assertNoRoute();
        });
        routing.promote(promotion(run, 0), "alice");
    }

    @Test
    void inFlightSourceWriterCausesNowaitConflictWithoutDeadlocking() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        withHeldTransaction(() -> jdbc.update("UPDATE payments SET merchant_reference=merchant_reference WHERE id=?", payment), () -> {
            assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
            assertNoRoute();
        });
        routing.promote(promotion(run, 0), "alice");
    }

    @Test
    void runRowLockWithoutTableWritesStillCausesBoundedPromotionConflict() throws Exception {
        createAndConsume();
        UUID run = build();
        withHeldTransaction(() -> jdbc.queryForObject("SELECT run_id FROM projection_rebuild_runs WHERE run_id=? FOR UPDATE", UUID.class, run), () -> {
            assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
            assertNoRoute();
        });
        routing.promote(promotion(run, 0), "alice");
    }

    @Test
    void positionFirstProcessorCannotDeadlockTheCutoverFence() throws Exception {
        createAndConsume();
        UUID run = build();
        withHeldTransaction(() -> failureRepository.lockPosition("primary", BASELINE, badRecord(8)), () -> {
            assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
            assertNoRoute();
        });
        routing.promote(promotion(run, 0), "alice");
    }

    @Test
    void oldRepeatableReadSnapshotCannotLockAndMutateRunAfterEnrollmentCommits() throws Exception {
        createAndConsume();
        UUID run = build();
        var oldSnapshot = new TransactionTemplate(transactions);
        oldSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> oldSnapshot.executeWithoutResult(tx -> {
                assertThat(jdbc.queryForObject("SELECT routing_enrolled FROM projection_rebuild_runs WHERE run_id=?", Boolean.class, run)).isFalse();
                var cutover = executor.submit(() -> routing.promote(promotion(run, 0), "alice"));
                try { assertThat(cutover.get(10, TimeUnit.SECONDS).revision()).isEqualTo(1); }
                catch (Exception failure) { throw new AssertionError(failure); }
                jdbc.queryForObject("SELECT routing_enrolled FROM projection_rebuild_runs WHERE run_id=? FOR UPDATE", Boolean.class, run);
            })).isInstanceOf(DataAccessException.class).satisfies(failure -> {
                Throwable cause = failure;
                while (cause != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("40001");
            });
        }
        assertThat(routing.status().revision()).isEqualTo(1);
        assertThat(rebuild.status(run).generation()).isZero();
    }

    @Test
    void promotionUsesFreshReadCommittedSnapshotInsideAnOlderRepeatableReadCaller() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        var oldCaller = new TransactionTemplate(transactions);
        oldCaller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newSingleThreadExecutor()) {
            oldCaller.executeWithoutResult(tx -> {
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?", Long.class, payment)).isZero();
                var advance = executor.submit(() -> {
                    payments.authorizePayment(payment);
                    consumer.consume(sourceEvent(payment, 1));
                    return true;
                });
                try { assertThat(advance.get(10, TimeUnit.SECONDS)).isTrue(); }
                catch (Exception failure) { throw new AssertionError(failure); }
                // The caller still observes version zero. Routing must suspend that snapshot.
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?", Long.class, payment)).isZero();
                assertConflict(() -> routing.promote(promotion(run, 0), "alice"));
            });
        }
        assertNoRoute();
        assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?", Long.class, payment)).isEqualTo(1);
    }

    @Test
    void rollbackUsesFreshReadCommittedSnapshotInsideAnOlderRepeatableReadCaller() throws Exception {
        UUID payment = createAndConsume();
        UUID run = build();
        routing.promote(promotion(run, 0), "alice");
        var oldCaller = new TransactionTemplate(transactions);
        oldCaller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newSingleThreadExecutor()) {
            oldCaller.executeWithoutResult(tx -> {
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?", Long.class, payment)).isZero();
                var advance = executor.submit(() -> payments.authorizePayment(payment));
                try { assertThat(advance.get(10, TimeUnit.SECONDS).status()).isEqualTo(com.fincore.payments.domain.PaymentStatus.AUTHORIZED); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?", Long.class, payment)).isZero();
                assertConflict(() -> routing.rollback(rollback(1), "bob"));
            });
        }
        assertThat(routing.status().revision()).isEqualTo(1);
        assertThat(routing.status().activeConsumer()).isEqualTo(shadow(run));
        assertThat(count("projection_route_audit")).isEqualTo(1);
    }

    @Test
    void concurrentPromotionRequestsCommitOnlyOneDecisionAndExactRetryReplaysIt() throws Exception {
        createAndConsume();
        UUID run = build();
        var command = promotion(run, 0);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> promoteWhenReleased(command, start));
            var second = executor.submit(() -> promoteWhenReleased(command, start));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).contains(true);
        }
        assertThat(routing.promote(command, "alice").replayed()).isTrue();
        assertThat(count("projection_routes")).isEqualTo(1);
        assertThat(count("projection_route_audit")).isEqualTo(1);
    }

    @Test
    void configuredNamespaceCannotReinterpretAnotherSourcesRun() throws Exception {
        createAndConsume();
        UUID run = build();
        var other = new ProjectionRoutingService(routeRepository, rebuildRepository, reconciliation, kafka, "secondary",operatingProperties);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                other.promote(promotion(run, 0), "alice"))).isInstanceOf(ProjectionRoutingNotFoundException.class);
        assertNoRoute();
    }

    @Test
    void unboundedIdentityRevisionAndMultilineReasonsAreRejectedBeforeChangingAnything() {
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> routing.promote(null, "alice")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.promote(promotion(run, 0), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(), run, 0, Long.MAX_VALUE, "overflow"), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(), 0, "split\nreason"), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.promote(promotion(run, 0), "a".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertNoRoute();
    }

    @Test
    void replacementAtomicallyHandsMaintenanceToTheNewShadowAndRetainsOldEvidence() throws Exception {
        UUID payment=createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        UUID next=build();
        String evidence=targetImage(first);
        var result=routing.replace(replacement(first,next,2),"alice");
        assertThat(result.action()).isEqualTo("REPLACE");
        assertThat(result.revision()).isEqualTo(3);
        assertThat(routing.status().shadowRunId()).isEqualTo(next);
        assertThat(routing.status().activeConsumer()).isEqualTo(shadow(next));
        assertThat(routing.target(first).lifecycle()).isEqualTo("RETIRED");
        assertThat(routing.target(next).lifecycle()).isEqualTo("MAINTAINED");
        assertThat(rebuild.validate(first).promotion()).isEqualTo("RETIRED_EVIDENCE_RETAINED");
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment,1));
        assertVersions(payment,next,1);
        assertThat(targetImage(first)).isEqualTo(evidence);
        assertThat(receipts.findProjection(shadow(first),payment).orElseThrow().paymentVersion()).isZero();
        assertThat(count("ledger_transactions")).isZero();
        assertThat(jdbc.queryForObject("SELECT previous_run_id FROM projection_route_audit WHERE revision=3",UUID.class))
                .isEqualTo(first);
    }

    @Test
    void activeShadowCannotBeReplacedOrRetiredBeforeExplicitRollback() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        assertConflict(() -> routing.replace(replacement(first,next,1),"alice"));
        assertConflict(() -> routing.retire(retirement(first,1),"alice"));
        assertThat(routing.status().shadowRunId()).isEqualTo(first);
        assertThat(routing.target(next).lifecycle()).isEqualTo("ISOLATED");
        assertThat(count("projection_route_audit")).isEqualTo(1);
    }

    @Test
    void retirementStopsFanoutButPreservesBaselineReadsAndAllTargetEvidence() throws Exception {
        UUID payment=createAndConsume();
        UUID run=build();
        routing.promote(promotion(run,0),"alice");
        routing.rollback(rollback(1),"alice");
        String image=targetImage(run);
        routing.retire(retirement(run,2),"alice");
        assertThat(routing.status().revision()).isEqualTo(3);
        assertThat(routing.status().shadowRunId()).isNull();
        assertThat(routing.status().shadowConsumer()).isNull();
        assertThat(routing.status().activeConsumer()).isEqualTo(BASELINE);
        assertThat(receipts.maintainedShadow()).isEmpty();
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment,1));
        assertThat(consumer.getProjection(payment).paymentVersion()).isEqualTo(1);
        assertThat(targetImage(run)).isEqualTo(image);
        assertThat(routing.target(run).lifecycle()).isEqualTo("RETIRED");
    }

    @Test
    void freshShadowCanBeEnrolledAfterRetirementWithoutResettingTheRevision() throws Exception {
        createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        routing.retire(retirement(first,2),"alice");
        UUID next=build();
        routing.promote(promotion(next,3),"bob");
        assertThat(routing.status().revision()).isEqualTo(4);
        assertThat(routing.target(first).lifecycle()).isEqualTo("RETIRED");
        assertThat(routing.target(next).lifecycle()).isEqualTo("MAINTAINED");
        routing.rollback(rollback(4),"bob");
        assertThat(routing.status().revision()).isEqualTo(5);
    }

    @Test
    void retiredTargetsCannotBeReenrolledReplacedBackOrResumeRebuilding() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        routing.replace(replacement(first,next,2),"alice");
        routing.rollback(rollback(3),"alice");
        assertConflict(() -> routing.replace(replacement(next,first,4),"alice"));
        routing.retire(retirement(next,4),"alice");
        assertConflict(() -> routing.promote(promotion(first,5),"alice"));
        assertConflict(() -> routing.promote(promotion(next,5),"alice"));
        assertThatThrownBy(() -> rebuild.step(first,100)).isInstanceOf(ShadowRebuildConflictException.class);
        assertThatThrownBy(() -> rebuild.catchUp(first,0,UUID.randomUUID(),"builder","retired input"))
                .isInstanceOf(ShadowRebuildConflictException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_runs SET current_generation=1 WHERE run_id=?",first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_items SET status=status WHERE run_id=?",first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exactReplacementReplayReturnsOriginalDecisionAfterLaterRetirement() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        var command=replacement(first,next,2);
        var original=routing.replace(command,"alice");
        routing.rollback(rollback(3),"bob");
        routing.retire(retirement(next,4),"bob");
        var replay=routing.replace(command,"alice");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.revision()).isEqualTo(3);
        assertThat(replay.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(routing.status().revision()).isEqualTo(5);
        assertThat(receipts.maintainedShadow()).isEmpty();
    }

    @Test
    void exactRetirementAndOriginalPromotionReplaysSurviveNewEnrollment() throws Exception {
        createAndConsume();
        UUID first=build();
        var original=promotion(first,0);
        routing.promote(original,"alice");
        routing.rollback(rollback(1),"alice");
        var command=retirement(first,2);
        var retired=routing.retire(command,"alice");
        UUID next=build();
        routing.promote(promotion(next,3),"bob");
        assertThat(routing.retire(command,"alice").occurredAt()).isEqualTo(retired.occurredAt());
        assertThat(routing.retire(command,"alice").replayed()).isTrue();
        assertThat(routing.promote(original,"alice").revision()).isEqualTo(1);
        assertThat(routing.status().revision()).isEqualTo(4);
        assertThat(routing.status().shadowRunId()).isEqualTo(next);
    }

    @Test
    void lifecycleRequestIdentityCannotChangeActorPreviousTargetOrReason() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        var command=replacement(first,next,2);
        routing.replace(command,"alice");
        assertConflict(() -> routing.replace(command,"bob"));
        assertConflict(() -> routing.replace(new ProjectionReplacementCommand(command.requestId(),UUID.randomUUID(),next,0,2,command.reason()),"alice"));
        assertConflict(() -> routing.replace(new ProjectionReplacementCommand(command.requestId(),first,next,1,2,command.reason()),"alice"));
        assertConflict(() -> routing.replace(new ProjectionReplacementCommand(command.requestId(),first,next,0,2,"different reason"),"alice"));
        assertConflict(() -> routing.retire(new ProjectionRetirementCommand(command.requestId(),next,2,command.reason()),"alice"));
        routing.rollback(rollback(3),"alice");
        var retire=retirement(next,4);
        routing.retire(retire,"alice");
        assertConflict(() -> routing.retire(retire,"bob"));
        assertConflict(() -> routing.retire(new ProjectionRetirementCommand(retire.requestId(),first,4,retire.reason()),"alice"));
        assertConflict(() -> routing.retire(new ProjectionRetirementCommand(retire.requestId(),next,4,"changed"),"alice"));
        assertThat(routing.status().revision()).isEqualTo(5);
    }

    @Test
    void replacementAndRetirementRequireExactCurrentRevisionAndTarget() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        assertConflict(() -> routing.replace(replacement(first,next,1),"alice"));
        assertConflict(() -> routing.replace(replacement(UUID.randomUUID(),next,2),"alice"));
        assertConflict(() -> routing.replace(replacement(first,first,2),"alice"));
        assertConflict(() -> routing.retire(retirement(first,1),"alice"));
        assertConflict(() -> routing.retire(retirement(next,2),"alice"));
        assertThat(routing.status().revision()).isEqualTo(2);
    }

    @Test
    void replacementRejectsIncompleteOrStaleCandidateThenAcceptsFreshCatchUp() throws Exception {
        UUID payment=createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        UUID next=UUID.randomUUID();
        rebuild.create(next,"builder","pending replacement");
        assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
        rebuild.step(next,100);
        payments.authorizePayment(payment);
        consumer.consume(sourceEvent(payment,1));
        assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
        rebuild.catchUp(next,0,UUID.randomUUID(),"builder","fresh source");
        rebuild.step(next,100);
        assertConflict(() -> routing.replace(replacement(first,next,2),"alice")); // stale generation
        routing.replace(new ProjectionReplacementCommand(UUID.randomUUID(),first,next,1,2,"caught up"),"alice");
        assertVersions(payment,next,1);
    }

    @Test
    void retirementAndReplacementRefuseUnconsumedSourceChanges() throws Exception {
        UUID payment=createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        payments.authorizePayment(payment);
        UUID next=build();
        assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
        assertConflict(() -> routing.retire(retirement(first,2),"alice"));
        consumer.consume(sourceEvent(payment,1));
        routing.replace(replacement(first,next,2),"alice");
    }

    @Test
    void openGapsBlockBothLifecycleActionsWithoutClosingTheIncident() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        failures.failed(badRecord(800),new InvalidPaymentEventException("invalid retained original"));
        assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
        assertConflict(() -> routing.retire(retirement(first,2),"alice"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumer_event_failures WHERE resolved_at IS NULL",Long.class)).isEqualTo(1);
        assertThat(routing.status().revision()).isEqualTo(2);
    }

    @Test
    void sourceLessReadsCannotDisappearDuringReplacement() throws Exception {
        createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        var external=externalEvent();
        consumer.consume(external);
        routing.rollback(rollback(1),"alice");
        UUID next=build();
        assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
        assertThat(consumer.getProjection(external.paymentId()).lastEventId()).isEqualTo(external.eventId());
        assertThat(routing.target(next).lifecycle()).isEqualTo("ISOLATED");
    }

    @Test
    void busyLiveConsumerMakesReplacementAndRetirementBoundedConflicts() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        withHeldTransaction(receipts::lockRouting,() -> {
            assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
            assertConflict(() -> routing.retire(retirement(first,2),"alice"));
        });
        assertThat(routing.status().revision()).isEqualTo(2);
        routing.replace(replacement(first,next,2),"alice");
    }

    @Test
    void replacementWithLockedCandidateRowFailsWithoutPartialEnrollment() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        withHeldTransaction(() -> jdbc.queryForObject("SELECT run_id FROM projection_rebuild_runs WHERE run_id=? FOR UPDATE",UUID.class,next),() ->
                assertConflict(() -> routing.replace(replacement(first,next,2),"alice")));
        assertThat(routing.target(first).lifecycle()).isEqualTo("MAINTAINED");
        assertThat(routing.target(next).lifecycle()).isEqualTo("ISOLATED");
    }

    @Test
    void concurrentLifecycleCommandsAcceptOneRevisionAndExactRetryReturnsIt() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        var replace=replacement(first,next,2);
        var retire=retirement(first,2);
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> lifecycleWhenReleased(() -> routing.replace(replace,"alice"),start));
            var b=executor.submit(() -> lifecycleWhenReleased(() -> routing.retire(retire,"alice"),start));
            start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(routing.status().revision()).isEqualTo(3);
        if (routing.status().shadowRunId()==null) assertThat(routing.retire(retire,"alice").replayed()).isTrue();
        else assertThat(routing.replace(replace,"alice").replayed()).isTrue();
        assertThat(count("projection_route_audit")).isEqualTo(3);
    }

    @Test
    void databaseRefusesUnenrollmentOrChangingHistoricalRetirementDecisions() throws Exception {
        createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        routing.retire(retirement(first,2),"alice");
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_runs SET routing_enrolled=false WHERE run_id=?",first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_route_audit SET previous_run_id=NULL WHERE action='RETIRE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projection_rebuild_generations WHERE run_id=?",first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rawSqlCannotRetireAnActiveShadowOrReenrollARetiredOne() throws Exception {
        createAndConsume();
        UUID run=build();
        routing.promote(promotion(run,0),"alice");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                routeRepository.switchRoute(UUID.randomUUID(),"RETIRE",1,run,0,"sql-operator","illegal retirement",BASELINE)))
                .isInstanceOf(DataIntegrityViolationException.class);
        routing.rollback(rollback(1),"alice");
        routing.retire(retirement(run,2),"alice");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            UUID request=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO projection_route_audit(request_id,action,expected_revision,run_id,build_generation,
                        actor,reason,revision,active_consumer,previous_run_id)
                    VALUES (?,'PROMOTE',3,?,0,'sql-operator','illegal reenrollment',4,?,NULL)
                    """,request,run,shadow(run));
            jdbc.update("UPDATE projection_routes SET revision=4,shadow_run_id=?,active_consumer=?,last_request_id=?",run,shadow(run),request);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(routing.status().revision()).isEqualTo(3);
    }

    @Test
    void failedReplacementCommitRollsBackAuditEnrollmentAndPointerTogether() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        jdbc.execute("CREATE FUNCTION reject_test_replacement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected route write failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_replacement BEFORE UPDATE ON projection_routes FOR EACH ROW EXECUTE FUNCTION reject_test_replacement()");
        try {
            assertThatThrownBy(() -> routing.replace(replacement(first,next,2),"alice")).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_replacement ON projection_routes");
            jdbc.execute("DROP FUNCTION reject_test_replacement()");
        }
        assertThat(routing.target(next).lifecycle()).isEqualTo("ISOLATED");
        assertThat(routing.target(first).lifecycle()).isEqualTo("MAINTAINED");
        assertThat(routing.status().revision()).isEqualTo(2);
        assertThat(count("projection_route_audit")).isEqualTo(2);
    }

    @Test
    void retiredTargetsRemainFrozenWhenANewerTargetIsServing() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        routing.replace(replacement(first,next,2),"alice");
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_generations SET state=state WHERE run_id=?",first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rebuild.validate(first).promotion()).isEqualTo("RETIRED_EVIDENCE_RETAINED");
        assertThat(routing.target(next).lifecycle()).isEqualTo("MAINTAINED");
        assertThat(receipts.maintainedShadow()).contains(shadow(next));
    }

    @Test
    void repeatedReplacementsKeepOnlyTheLatestShadowWarmAndBaselineRollbackAvailable() throws Exception {
        UUID payment=createAndConsume();
        UUID first=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        UUID second=build();
        routing.replace(replacement(first,second,2),"alice");
        routing.rollback(rollback(3),"alice");
        UUID third=build();
        routing.replace(replacement(second,third,4),"alice");
        payments.authorizePayment(payment);
        var event=sourceEvent(payment,1);
        consumer.consume(event);
        assertThat(consumer.consume(event)).isFalse();
        assertVersions(payment,third,1);
        assertThat(receipts.findProjection(shadow(first),payment).orElseThrow().paymentVersion()).isZero();
        assertThat(receipts.findProjection(shadow(second),payment).orElseThrow().paymentVersion()).isZero();
        routing.rollback(rollback(5),"alice");
        assertThat(consumer.getProjection(payment).paymentVersion()).isEqualTo(1);
        assertThat(routing.status().revision()).isEqualTo(6);
    }

    @Test
    void v10UpgradePreservesExistingDecisionsAndPermitsAuditedRetirement() throws Exception {
        String schema="routing_upgrade_" + UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("routing_upgrade_[0-9a-f]{32}");
        try (var connection=dataSource.getConnection()) {
            String originalSchema=connection.getSchema();
            var ds=new SingleConnectionDataSource(connection,true);
            var isolated=new JdbcTemplate(ds);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            isolated.execute("CREATE SCHEMA \"" + schema + "\"");
            try {
                Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("10").load().migrate();
                connection.setSchema(schema);
                UUID run=UUID.randomUUID();
                UUID promoted=UUID.randomUUID();
                UUID rolledBack=UUID.randomUUID();
                var plans=new ProjectionRebuildRepository(isolated);
                tx.executeWithoutResult(status -> {
                    plans.insertRun(run,shadow(run),"primary",kafka.topic(),"builder","upgrade fixture");
                    plans.beginGeneration(run,0,run,null,"builder","upgrade fixture",new com.fincore.payments.dto.ReconciliationGapContext(0,0,0,0));
                    plans.finishPlan(run,0);
                    isolated.update("UPDATE projection_rebuild_runs SET routing_enrolled=true WHERE run_id=?",run);
                    isolated.update("""
                            INSERT INTO projection_route_audit(request_id,action,expected_revision,run_id,build_generation,
                                actor,reason,revision,active_consumer) VALUES (?,'PROMOTE',0,?,0,'alice','v10 promotion',1,?)
                            """,promoted,run,shadow(run));
                    isolated.update("INSERT INTO projection_routes VALUES (true,1,?,?,?)",run,shadow(run),promoted);
                    isolated.update("""
                            INSERT INTO projection_route_audit(request_id,action,expected_revision,run_id,build_generation,
                                actor,reason,revision,active_consumer) VALUES (?,'ROLLBACK',1,?,0,'alice','v10 rollback',2,?)
                            """,rolledBack,run,BASELINE);
                    isolated.update("UPDATE projection_routes SET revision=2,active_consumer=?,last_request_id=?",BASELINE,rolledBack);
                });
                String before=isolated.queryForObject("SELECT jsonb_agg(to_jsonb(a) ORDER BY revision)::text FROM projection_route_audit a",String.class);
                Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                assertThat(isolated.queryForObject("SELECT jsonb_agg(to_jsonb(a)-'previous_run_id' ORDER BY revision)::text FROM projection_route_audit a",String.class))
                        .isEqualTo(before);
                assertThat(isolated.queryForObject("SELECT previous_run_id FROM projection_route_audit WHERE revision=2",UUID.class)).isEqualTo(run);
                var routes=new ProjectionRoutingRepository(isolated);
                var service=new ProjectionRoutingService(routes,plans,new ProjectionReconciliationRepository(isolated),kafka,"primary",operatingProperties);
                var replay=tx.execute(status -> service.promote(new ProjectionPromotionCommand(promoted,run,0,0,"v10 promotion"),"alice"));
                assertThat(replay.replayed()).isTrue();
                tx.executeWithoutResult(status -> service.retire(retirement(run,2),"alice"));
                assertThat(routes.view().revision()).isEqualTo(3);
                assertThat(routes.view().shadowRunId()).isNull();
                assertThat(routes.lifecycle(run)).isEqualTo("RETIRED");
            } finally {
                connection.setSchema(originalSchema);
                isolated.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    @Test
    void replacementSuspendsAnOlderCallerSnapshotAndRechecksCurrentSource() throws Exception {
        UUID payment=createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"alice");
        routing.rollback(rollback(1),"alice");
        var caller=new TransactionTemplate(transactions);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor=Executors.newSingleThreadExecutor()) {
            caller.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?",Long.class,payment)).isZero();
                try {
                    executor.submit(() -> {
                        payments.authorizePayment(payment);
                        try { consumer.consume(sourceEvent(payment,1)); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }).get(10,TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?",Long.class,payment)).isZero();
                assertConflict(() -> routing.replace(replacement(first,next,2),"alice"));
            });
        }
        assertThat(routing.status().revision()).isEqualTo(2);
    }

    @Test
    void retirementSuspendsAnOlderCallerSnapshotRatherThanAcceptingStaleAgreement() throws Exception {
        UUID payment=createAndConsume();
        UUID run=build();
        routing.promote(promotion(run,0),"alice");
        routing.rollback(rollback(1),"alice");
        var caller=new TransactionTemplate(transactions);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor=Executors.newSingleThreadExecutor()) {
            caller.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?",Long.class,payment)).isZero();
                try { executor.submit(() -> payments.authorizePayment(payment)).get(10,TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id=?",Long.class,payment)).isZero();
                assertConflict(() -> routing.retire(retirement(run,2),"alice"));
            });
        }
        assertThat(routing.target(run).lifecycle()).isEqualTo("MAINTAINED");
    }

    @Test
    void lifecycleValidationRejectsMissingIdentityOverflowAndInvalidActorBeforeWrites() {
        UUID old=UUID.randomUUID();
        UUID next=UUID.randomUUID();
        assertThatThrownBy(() -> routing.replace(null,"alice")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.retire(null,"alice")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.replace(new ProjectionReplacementCommand(UUID.randomUUID(),null,next,0,0,"reviewed"),"alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.replace(new ProjectionReplacementCommand(UUID.randomUUID(),old,next,-1,0,"reviewed"),"alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),old,Long.MAX_VALUE,"reviewed"),"alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.replace(replacement(old,next,0),"bad\nactor")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),old,0,"bad\u2028reason"),"alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routing.target(next)).isInstanceOf(ProjectionRoutingNotFoundException.class);
        assertNoRoute();
    }

    @Test
    void comparisonBudgetRefusesOversizedSourceBeforeEnrollmentOrAuditWrites() throws Exception {
        createAndConsume();
        createAndConsume();
        UUID run=build();
        assertThatThrownBy(() -> budgeted(1,service -> service.promote(promotion(run,0),"operator")))
                .isInstanceOf(com.fincore.payments.exception.ProjectionComparisonBudgetException.class);
        assertNoRoute();
        assertThat(routing.target(run).lifecycle()).isEqualTo("ISOLATED");
    }

    @Test
    void exactBudgetBoundaryIsAllowedAndHistoricalReplayDoesNotRecompareGrowingData() throws Exception {
        createAndConsume();
        UUID run=build();
        var command=promotion(run,0);
        assertThat(budgeted(1,service -> service.promote(command,"operator")).revision()).isEqualTo(1);
        createAndConsume();
        assertThat(budgeted(1,service -> service.promote(command,"operator")).replayed()).isTrue();
        assertThatThrownBy(() -> budgeted(1,service -> service.rollback(rollback(1),"operator")))
                .isInstanceOf(com.fincore.payments.exception.ProjectionComparisonBudgetException.class);
        assertThat(budgeted(2,service -> service.rollback(rollback(1),"operator")).revision()).isEqualTo(2);
    }

    @Test
    void sourceLessBaselineRowsAreIncludedInTheComparisonBudget() throws Exception {
        createAndConsume();
        consumer.consume(externalEvent());
        UUID run=build();
        assertThatThrownBy(() -> budgeted(1,service -> service.promote(promotion(run,0),"operator")))
                .isInstanceOf(com.fincore.payments.exception.ProjectionComparisonBudgetException.class);
        assertNoRoute();
    }

    @Test
    void oversizedCandidateViewIsRefusedBeforeFullManifestComparison() throws Exception {
        createAndConsume();
        UUID run=build();
        var extra=externalEvent();
        receipts.insertReceipt(shadow(run),extra,mapper.writeValueAsString(extra));
        receipts.applyProjection(shadow(run),extra);
        assertThatThrownBy(() -> budgeted(1,service -> service.promote(promotion(run,0),"operator")))
                .isInstanceOf(com.fincore.payments.exception.ProjectionComparisonBudgetException.class);
        assertNoRoute();
    }

    @Test
    void replacementAndRetirementApplyTheSameBudgetWithoutReleasingMaintenance() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"operator");
        routing.rollback(rollback(1),"operator");
        createAndConsume();
        assertThatThrownBy(() -> budgeted(1,service -> service.replace(replacement(first,next,2),"operator")))
                .isInstanceOf(com.fincore.payments.exception.ProjectionComparisonBudgetException.class);
        assertThatThrownBy(() -> budgeted(1,service -> service.retire(retirement(first,2),"operator")))
                .isInstanceOf(com.fincore.payments.exception.ProjectionComparisonBudgetException.class);
        assertThat(routing.status().revision()).isEqualTo(2);
        assertThat(routing.target(first).lifecycle()).isEqualTo("MAINTAINED");
        assertThat(routing.target(next).lifecycle()).isEqualTo("ISOLATED");
    }

    @Test
    void httpOperationMeasurementCountsDeferredCommitFailureRatherThanAcceptedSuccess() throws Exception {
        createAndConsume();
        UUID run=build();
        var metrics=new com.fincore.payments.service.ProjectionRoutingMetrics();
        var controller=new com.fincore.payments.controller.ProjectionRoutingController(routing,metrics);
        var command=promotion(run,0);
        jdbc.execute("CREATE FUNCTION reject_test_route_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected deferred route failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_test_route_commit AFTER INSERT ON projection_routes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_test_route_commit()");
        try {
            assertThatThrownBy(() -> controller.promote(command,() -> "operator"))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("injected deferred route failure");
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_route_commit ON projection_routes");
            jdbc.execute("DROP FUNCTION reject_test_route_commit()");
        }
        var failed=metrics.snapshot().operations().getFirst();
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.accepted()).isZero();
        assertThat(failed.failed()).isEqualTo(1);
        assertNoRoute();
        controller.promote(command,() -> "operator");
        assertThat(metrics.snapshot().operations().getFirst().accepted()).isEqualTo(1);
    }

    private com.fincore.payments.dto.ProjectionRoutingResult budgeted(int maximum,
            java.util.function.Function<ProjectionRoutingService,com.fincore.payments.dto.ProjectionRoutingResult> command) {
        // Explicitly supply the public service's transaction contract for this custom test configuration.
        var transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        var service=new ProjectionRoutingService(routeRepository,rebuildRepository,reconciliation,kafka,"primary",
                new com.fincore.payments.config.ProjectionOperationsProperties(30,maximum));
        return transaction.execute(status -> command.apply(service));
    }

    private boolean lifecycleWhenReleased(Runnable action,CountDownLatch start) throws Exception {
        start.await();
        try { action.run(); return true; }
        catch (ProjectionRoutingConflictException conflict) { return false; }
    }

    private String targetImage(UUID run) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'receipts',(SELECT jsonb_agg(to_jsonb(r) ORDER BY event_id) FROM consumed_payment_events r WHERE consumer_name=?),
                    'projections',(SELECT jsonb_agg(to_jsonb(p) ORDER BY payment_id) FROM payment_event_projections p WHERE consumer_name=?),
                    'manifest',(SELECT jsonb_agg(to_jsonb(i) ORDER BY generation,payment_id) FROM projection_rebuild_items i WHERE run_id=?),
                    'generations',(SELECT jsonb_agg(to_jsonb(g) ORDER BY generation) FROM projection_rebuild_generations g WHERE run_id=?))::text
                """,String.class,shadow(run),shadow(run),run,run);
    }

    private ProjectionReplacementCommand replacement(UUID previous,UUID next,long revision) {
        return new ProjectionReplacementCommand(UUID.randomUUID(),previous,next,0,revision,"reviewed replacement");
    }

    private ProjectionRetirementCommand retirement(UUID run,long revision) {
        return new ProjectionRetirementCommand(UUID.randomUUID(),run,revision,"reviewed retirement");
    }

    private UUID createAndConsume() throws Exception {
        UUID payment = payments.createPayment("routing-" + UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-ROUTING-REFERENCE", BigDecimal.TEN, "INR")).payment().id();
        consumer.consume(sourceEvent(payment, 0));
        return payment;
    }

    private UUID build() {
        UUID run = UUID.randomUUID();
        rebuild.create(run, "builder", "frozen cutover candidate");
        rebuild.step(run, 100);
        return run;
    }

    private ProjectionPromotionCommand promotion(UUID run, long expectedRevision) {
        return new ProjectionPromotionCommand(UUID.randomUUID(), run, 0, expectedRevision, "reviewed promotion");
    }

    private ProjectionRollbackCommand rollback(long expectedRevision) {
        return new ProjectionRollbackCommand(UUID.randomUUID(), expectedRevision, "reviewed rollback");
    }

    private PaymentEvent sourceEvent(UUID payment, long version) throws Exception {
        return mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id=? AND payment_version=?",
                String.class, payment, version), PaymentEvent.class);
    }

    private PaymentEvent externalEvent() {
        UUID payment = UUID.randomUUID();
        var now = java.time.Instant.now().truncatedTo(ChronoUnit.MICROS);
        var data = new PaymentResponse(payment, "EXTERNAL-PRIVATE-REFERENCE", BigDecimal.ONE, "USD",
                com.fincore.payments.domain.PaymentStatus.CREATED, now, now);
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1, payment, 0, now, data);
    }

    private ConsumerRecord<String,String> badRecord(long offset) {
        return new ConsumerRecord<>(kafka.topic(), 0, offset, "private-invalid-key", "private-invalid-json");
    }

    private void assertVersions(UUID payment, UUID run, long version) {
        assertThat(receipts.findProjection(BASELINE, payment).orElseThrow().paymentVersion()).isEqualTo(version);
        assertThat(receipts.findProjection(shadow(run), payment).orElseThrow().paymentVersion()).isEqualTo(version);
    }

    private void insertAudit(UUID request, UUID run, String action, long expected, String active) {
        jdbc.update("""
                INSERT INTO projection_route_audit(request_id,action,expected_revision,run_id,build_generation,
                    actor,reason,revision,active_consumer) VALUES (?,?,?,?,0,'database-operator','test guard',?,?)
                """, request, action, expected, run, expected + 1, active);
    }

    private void withHeldTransaction(Runnable lock, Runnable assertions) throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                lock.run();
                acquired.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test lock release timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                assertions.run();
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private boolean promoteWhenReleased(ProjectionPromotionCommand command, CountDownLatch start) throws Exception {
        start.await();
        try { routing.promote(command, "alice"); return true; }
        catch (ProjectionRoutingConflictException conflict) { return false; }
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ProjectionRoutingConflictException.class);
    }

    private void assertNoRoute() {
        assertThat(routing.status().revision()).isZero();
        assertThat(count("projection_routes")).isZero();
        assertThat(count("projection_route_audit")).isZero();
    }

    private String shadow(UUID run) { return "shadow-" + run; }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private long receiptCount(String identity) {
        return jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE consumer_name=?", Long.class, identity);
    }
}
