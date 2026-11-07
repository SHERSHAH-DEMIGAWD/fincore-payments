package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fincore.payments.domain.Payment;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.dto.CreatePaymentRequest;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.exception.ShadowRebuildConflictException;
import com.fincore.payments.exception.ShadowRebuildNotFoundException;
import com.fincore.payments.repository.OutboxRepository;
import com.fincore.payments.repository.PaymentRepository;
import com.fincore.payments.repository.PaymentEventConsumerRepository;
import com.fincore.payments.service.ConsumerFailureService;
import com.fincore.payments.service.PaymentEventConsumer;
import com.fincore.payments.service.PaymentService;
import com.fincore.payments.service.ProjectionRebuildService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real database assertions for isolated, resumable projection rebuilding. */
@SpringBootTest(properties = {"fincore.events.transport=local", "fincore.outbox.enabled=false"})
abstract class ProjectionRebuildDatabaseTests {
    @Autowired ProjectionRebuildService rebuild;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEventConsumer liveConsumer;
    @Autowired OutboxRepository outbox;
    @Autowired PaymentEventConsumerRepository receipts;
    @Autowired ConsumerFailureService failures;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.fincore.payments.repository.ProjectionRebuildRepository rebuildRepository;
    @Autowired com.fincore.payments.repository.ProjectionReconciliationRepository reconciliationRepository;
    @Autowired com.fincore.payments.service.PaymentEventRecordDecoder decoder;
    @Autowired com.fincore.payments.config.KafkaEventProperties kafka;

    @BeforeEach
    void clearDisposableDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs,
                    consumer_recovery_audit, consumer_event_failures, consumer_event_positions, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events, local_payment_event_inbox, payment_outbox,
                    ledger_entries, ledger_transactions, ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void emptyRunStillHasAnIsolatedIdentityAndDoesNotCertifyHistory() {
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "empty source review");
        rebuild.step(run, 50);
        assertThat(count("projection_rebuild_runs")).isEqualTo(1);
        assertThat(count("projection_rebuild_generations")).isEqualTo(1);
        assertThat(count("projection_rebuild_items")).isZero();
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
        assertThat(json(rebuild.status(run)).toString()).contains(shadow(run));
        assertThat(rebuild.validate(run).historicalCompleteness()).isEqualTo("NOT_CERTIFIED");
    }

    @Test
    void latestSnapshotBuildUsesFreshIdentityWithoutRepeatingLiveOrFinancialEffects() throws Exception {
        UUID payment = createPayment();
        payments.authorizePayment(payment);
        payments.capturePayment(payment);
        liveConsumer.consume(sourceEvent(payment, 2));
        var before = sourceAndLiveImage();
        UUID run = UUID.randomUUID();

        rebuild.create(run, "operator", "replace lost read model in isolation");
        rebuild.step(run, 50);

        assertThat(shadowCount(run, "consumed_payment_events")).isEqualTo(1);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE consumer_name = ?",
                Long.class, shadow(run))).isEqualTo(2);
        assertThat(count("projection_rebuild_items")).isEqualTo(1);
        assertThat(sourceAndLiveImage()).isEqualTo(before);
        assertThat(json(rebuild.status(run)).toString()).doesNotContain("PRIVATE-REFERENCE", "payload");
        var validation = rebuild.validate(run);
        assertThat(validation.status()).isEqualTo("MATCHES_CURRENT_SOURCE");
        assertThat(validation.shadowMismatches()).isZero();
        assertThat(validation.promotion()).isEqualTo("REQUIRES_AUTHENTICATED_PROMOTION");
    }

    @Test
    void originalRunIdentityReplaysExactIntentAndRejectsChangedIntent() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "reviewed source");
        rebuild.create(run, "operator", "reviewed source");
        assertThat(count("projection_rebuild_runs")).isEqualTo(1);
        assertThat(count("projection_rebuild_generations")).isEqualTo(1);
        assertThat(count("projection_rebuild_items")).isEqualTo(1);
        assertThatThrownBy(() -> rebuild.create(run, "another-operator", "reviewed source"))
                .isInstanceOf(ShadowRebuildConflictException.class);
        assertThatThrownBy(() -> rebuild.create(run, "operator", "changed reason"))
                .isInstanceOf(ShadowRebuildConflictException.class);
    }

    @Test
    void runCommandsCannotBeReinterpretedUnderAnotherConfiguredSourceOrTopic() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "fixed logical source scope");
        var otherSource = new ProjectionRebuildService(rebuildRepository, receipts, reconciliationRepository,
                decoder, mapper, kafka, "another-source", transactions);
        var otherTopic = new ProjectionRebuildService(rebuildRepository, receipts, reconciliationRepository,
                decoder, mapper, new com.fincore.payments.config.KafkaEventProperties("another-topic", kafka.consumerGroup(),
                kafka.sendTimeoutMs()), "primary", transactions);
        assertThatThrownBy(() -> otherSource.status(run)).isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThatThrownBy(() -> otherSource.create(run, "operator", "fixed logical source scope"))
                .isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThatThrownBy(() -> otherTopic.validate(run)).isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThatThrownBy(() -> otherTopic.catchUp(run, 0, UUID.randomUUID(), "operator", "changed scope"))
                .isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThat(count("projection_rebuild_generations")).isEqualTo(1);
    }

    @Test
    void planningFailureRollsBackRunGenerationAndManifestAsOneUnit() {
        createPayment();
        UUID run = UUID.randomUUID();
        installPlanningFailure();
        try {
            assertThatThrownBy(() -> rebuild.create(run, "operator", "atomic manifest planning"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("projection_rebuild_runs")).isZero();
            assertThat(count("projection_rebuild_generations")).isZero();
            assertThat(count("projection_rebuild_items")).isZero();
            assertThat(count("payments")).isEqualTo(1);
        } finally { removePlanningFailure(); }
        rebuild.create(run, "operator", "atomic manifest planning");
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
    }

    @Test
    void catchUpPlanningFailureRollsBackGenerationPointerAndCanRetryTheSameRequest() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "initial generation");
        rebuild.step(run, 100);
        payments.authorizePayment(payment);
        UUID request = UUID.randomUUID();
        installPlanningFailure();
        try {
            assertThatThrownBy(() -> rebuild.catchUp(run, 0, request, "operator", "atomic catch-up manifest"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rebuild.status(run).generation()).isZero();
            assertThat(count("projection_rebuild_generations")).isEqualTo(1);
            assertThat(count("projection_rebuild_items")).isEqualTo(1);
            assertThat(shadowCount(run, "consumed_payment_events")).isEqualTo(1);
        } finally { removePlanningFailure(); }
        rebuild.catchUp(run, 0, request, "operator", "atomic catch-up manifest");
        assertThat(rebuild.status(run).generation()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalCreatesSaveOneFrozenManifestAndReplayTheWinningResult() throws Exception {
        createPayment();
        UUID run = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                start.await(); return rebuild.create(run, "operator", "same approved build").replayed();
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("projection_rebuild_runs")).isEqualTo(1);
        assertThat(count("projection_rebuild_generations")).isEqualTo(1);
        assertThat(count("projection_rebuild_items")).isEqualTo(1);
    }

    @Test
    void concurrentChangedCreateIntentHasOneWinnerWithoutRewritingTheManifest() throws Exception {
        createPayment();
        UUID run = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> firstIntent = () -> createAttempt(run, "first reviewed purpose", start);
            Callable<Boolean> secondIntent = () -> createAttempt(run, "different reviewed purpose", start);
            var first = executor.submit(firstIntent);
            var second = executor.submit(secondIntent);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("projection_rebuild_generations")).isEqualTo(1);
        assertThat(count("projection_rebuild_items")).isEqualTo(1);
    }

    @Test
    void newRunCannotAdoptArtifactsAlreadyUsingItsReservedShadowIdentity() throws Exception {
        UUID payment = createPayment();
        var event = sourceEvent(payment, 0);
        UUID run = UUID.randomUUID();
        receipts.insertReceipt(shadow(run), event, mapper.writeValueAsString(event));
        receipts.applyProjection(shadow(run), event);
        assertThatThrownBy(() -> rebuild.create(run, "operator", "unexpected identity collision"))
                .isInstanceOf(ShadowRebuildConflictException.class);
        assertThat(count("projection_rebuild_runs")).isZero();
        assertThat(count("projection_rebuild_generations")).isZero();
        assertThat(count("projection_rebuild_items")).isZero();
        assertThat(shadowCount(run, "consumed_payment_events")).isEqualTo(1);
    }

    @Test
    void checkpointSurvivesIndependentCommandsAndRepeatedCompletedSteps() {
        createPayment();
        createPayment();
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "bounded replay");
        rebuild.step(run, 1);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(1);
        assertThat(rebuild.status(run).appliedItems()).isEqualTo(1);
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(2);
        assertThat(rebuild.status(run).nextPaymentId()).isNotNull();
        rebuild.step(run, 1);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(2);
        rebuild.step(run, 1);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(3);
        assertThat(rebuild.status(run).pendingItems()).isZero();
        assertThat(rebuild.status(run).nextPaymentId()).isNull();
        var before = image("consumed_payment_events", "payment_event_projections");
        rebuild.step(run, 100);
        assertThat(image("consumed_payment_events", "payment_event_projections")).isEqualTo(before);
    }

    @Test
    void frozenManifestDoesNotSilentlyReadNewerSourceVersionDuringStep() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "frozen version zero");
        payments.authorizePayment(payment);
        rebuild.step(run, 50);

        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE consumer_name = ?",
                Long.class, shadow(run))).isZero();
        var validation = rebuild.validate(run);
        assertThat(validation.status()).isEqualTo("SOURCE_CHANGED");
        assertThat(validation.sourceChanged()).isEqualTo(1);
    }

    @Test
    void newPaymentAfterPlanRemainsOutsideFrozenGenerationUntilCatchUp() {
        UUID original = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "capture initial source set");
        UUID later = createPayment();
        rebuild.step(run, 100);
        assertThat(jdbc.queryForList("SELECT payment_id FROM payment_event_projections WHERE consumer_name = ?",
                UUID.class, shadow(run))).containsExactly(original);
        var validation = rebuild.validate(run);
        assertThat(validation.status()).isEqualTo("SOURCE_CHANGED");
        assertThat(validation.sourceAdded()).isEqualTo(1);

        rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "include new source payment");
        rebuild.step(run, 100);
        assertThat(jdbc.queryForList("SELECT payment_id FROM payment_event_projections WHERE consumer_name = ?",
                UUID.class, shadow(run))).containsExactlyInAnyOrder(original, later);
    }

    @Test
    void catchUpAddsAnImmutableGenerationAndAdvancesOnlyTheShadowProjection() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "initial generation");
        rebuild.step(run, 100);
        var oldManifest = jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(i) ORDER BY to_jsonb(i)::text)::text FROM projection_rebuild_items i
                """, String.class);
        payments.authorizePayment(payment);
        rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "current authorization");
        rebuild.step(run, 100);
        assertThat(count("projection_rebuild_generations")).isEqualTo(2);
        assertThat(count("projection_rebuild_items")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE consumer_name = ?",
                Long.class, shadow(run))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections WHERE consumer_name = ?",
                Long.class, PaymentEventConsumer.CONSUMER_NAME)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(i) ORDER BY to_jsonb(i)::text)::text FROM projection_rebuild_items i WHERE generation = 0
                """, String.class)).isEqualTo(oldManifest);
    }

    @Test
    void catchUpRequestReplayRemainsIdempotentAfterItsGenerationHasBuilt() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "initial snapshot");
        rebuild.step(run, 100);
        payments.authorizePayment(payment);
        UUID request = UUID.randomUUID();
        rebuild.catchUp(run, 0, request, "operator", "advance snapshot");
        rebuild.step(run, 100);
        rebuild.catchUp(run, 0, request, "operator", "advance snapshot");
        assertThat(count("projection_rebuild_generations")).isEqualTo(2);
        assertThatThrownBy(() -> rebuild.catchUp(run, 0, request, "operator", "changed intent"))
                .isInstanceOf(ShadowRebuildConflictException.class);
        assertThatThrownBy(() -> rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "stale generation"))
                .isInstanceOf(ShadowRebuildConflictException.class);
    }

    @Test
    void pendingGenerationCannotBeAbandonedByCatchUp() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "must finish current work");
        assertThatThrownBy(() -> rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "skip pending"))
                .isInstanceOf(ShadowRebuildConflictException.class);
        assertThat(count("projection_rebuild_generations")).isEqualTo(1);
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
    }

    @Test
    void legacySourceWithoutAnEventRemainsBlockedWithoutSyntheticReceipt() {
        var payment = paymentRepository.saveAndFlush(Payment.create("PRIVATE-REFERENCE", BigDecimal.TEN, "INR"));
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "inspect legacy coverage");
        rebuild.step(run, 100);
        assertThat(count("projection_rebuild_items")).isEqualTo(1);
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
        assertThat(json(rebuild.status(run)).toString()).contains("BLOCKED");
        assertThat(rebuild.validate(run).status()).isEqualTo("BLOCKED_INPUTS");
        assertThat(count("payment_outbox")).isZero();
        assertThat(payments.getPayment(payment.getId())).isNotNull();
    }

    @Test
    void missingOriginalContractFieldsBlockTheItemAndNeverLeakPayload() {
        var payment = paymentRepository.saveAndFlush(Payment.create("PRIVATE-REFERENCE", BigDecimal.TEN, "INR"));
        var data = payments.getPayment(payment.getId());
        var event = event(payment.getId(), 0, data);
        ObjectNode payload = mapper.valueToTree(event);
        ((ObjectNode) payload.path("data")).remove("createdAt");
        jdbc.update("""
                INSERT INTO payment_outbox (event_id,payment_id,payment_version,event_type,occurred_at,payload)
                VALUES (?, ?, 0, 'PAYMENT_CREATED', ?, ?)
                """, event.eventId(), event.paymentId(), Timestamp.from(event.occurredAt()), payload.toString());
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "invalid historical contract");
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
        assertThat(json(rebuild.status(run)).toString()).contains("BLOCKED").doesNotContain("PRIVATE-REFERENCE", "payload");
    }

    @Test
    void validItemsCanBuildWhileBlockedItemsRemainAnExplicitObligation() {
        UUID valid = createPayment();
        paymentRepository.saveAndFlush(Payment.create("PRIVATE-LEGACY", BigDecimal.TEN, "INR"));
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "mixed source coverage");
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT payment_id FROM payment_event_projections WHERE consumer_name = ?",
                UUID.class, shadow(run))).containsExactly(valid);
        assertThat(json(rebuild.status(run)).toString()).contains("BLOCKED");
        assertThat(rebuild.status(run).blockedItems()).isEqualTo(1);
        assertThat(rebuild.status(run).appliedItems()).isEqualTo(1);
    }

    @Test
    void laterValidSourceEventCanRecoverBlockedCoverageInANewGeneration() {
        var legacy = paymentRepository.saveAndFlush(Payment.create("PRIVATE-LEGACY", BigDecimal.TEN, "INR"));
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "initial missing event");
        rebuild.step(run, 100);
        payments.authorizePayment(legacy.getId());
        rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "source now emits a current snapshot");
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE consumer_name = ?",
                Long.class, shadow(run))).isEqualTo(1);
        assertThat(count("projection_rebuild_items")).isEqualTo(2);
        assertThat(rebuild.validate(run).historicalCompleteness()).isEqualTo("NOT_CERTIFIED");
    }

    @Test
    void sourceBusinessDriftBlocksRebuildingFromAnOlderEnvelope() {
        UUID payment = createPayment();
        jdbc.update("UPDATE payments SET amount = 99.00 WHERE id = ?", payment);
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "do not choose disputed source data");
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
        assertThat(json(rebuild.status(run)).toString()).contains("BLOCKED");
    }

    @Test
    void sourceVersionHistoryGapsAreNotSilentlyCertifiedByLatestSnapshotBuild() {
        var legacy = paymentRepository.saveAndFlush(Payment.create("PRIVATE-LEGACY", BigDecimal.TEN, "INR"));
        jdbc.update("UPDATE payments SET version = 2 WHERE id = ?", legacy.getId());
        outbox.save(event(legacy.getId(), 2, payments.getPayment(legacy.getId())));
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "latest-state-only rebuild");
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(1);
        assertThat(rebuild.validate(run).historicalCompleteness()).isEqualTo("NOT_CERTIFIED");
        assertThat(rebuild.status(run).historyIncompleteItems()).isEqualTo(1);
        assertThat(count("payment_outbox")).isEqualTo(1);
    }

    @Test
    void consumerOnlyArtifactsAreNotCopiedIntoAuthoritativeSourceManifest() {
        UUID orphan = UUID.randomUUID();
        var at = java.time.Instant.parse("2026-10-01T10:00:00Z");
        liveConsumer.consume(event(orphan, 0, new PaymentResponse(orphan, "PRIVATE-ORPHAN", BigDecimal.TEN, "INR",
                com.fincore.payments.domain.PaymentStatus.CREATED, at, at)));
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "source is authoritative");
        rebuild.step(run, 100);
        assertThat(count("projection_rebuild_items")).isZero();
        assertThat(shadowCount(run, "payment_event_projections")).isZero();
        assertThat(count("payment_event_projections")).isEqualTo(1);
    }

    @Test
    void originalConsumerConflictDoesNotSilentlyAuthorizeReplacingTheLiveReceipt() throws Exception {
        UUID payment = createPayment();
        var original = sourceEvent(payment, 0);
        liveConsumer.consume(new PaymentEvent(UUID.randomUUID(), original.eventType(), 1, payment, 0,
                original.occurredAt(), original.data()));
        var before = sourceAndLiveImage();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "independent source-backed view");
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "consumed_payment_events")).isEqualTo(1);
        assertThat(sourceAndLiveImage()).isEqualTo(before);
    }

    @Test
    void shadowProjectionFailureRollsBackReceiptsAndCheckpointBeforeRetry() {
        createPayment();
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "failure atomicity");
        UUID failOnSecond = jdbc.queryForObject("SELECT id FROM payments ORDER BY id DESC LIMIT 1", UUID.class);
        var before = image("projection_rebuild_runs", "projection_rebuild_generations", "projection_rebuild_items");
        jdbc.execute("""
                CREATE FUNCTION test_reject_shadow_projection() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated shadow projection failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_shadow_projection BEFORE INSERT ON payment_event_projections
                FOR EACH ROW WHEN (NEW.consumer_name LIKE 'shadow-%%' AND NEW.payment_id = '%s'::uuid)
                EXECUTE FUNCTION test_reject_shadow_projection()
                """.formatted(failOnSecond));
        try {
            assertThatThrownBy(() -> rebuild.step(run, 100)).isInstanceOf(RuntimeException.class);
            assertThat(shadowCount(run, "consumed_payment_events")).isZero();
            assertThat(shadowCount(run, "payment_event_projections")).isZero();
            assertThat(image("projection_rebuild_runs", "projection_rebuild_generations", "projection_rebuild_items"))
                    .isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_shadow_projection ON payment_event_projections");
            jdbc.execute("DROP FUNCTION test_reject_shadow_projection()");
        }
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(2);
    }

    @Test
    void checkpointFailureRollsBackPreviouslyWrittenReceiptAndProjection() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "checkpoint rollback");
        var before = image("projection_rebuild_runs", "projection_rebuild_generations", "projection_rebuild_items");
        jdbc.execute("""
                CREATE FUNCTION test_reject_shadow_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated checkpoint failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_shadow_checkpoint BEFORE UPDATE ON projection_rebuild_items
                FOR EACH ROW WHEN (NEW.status = 'APPLIED') EXECUTE FUNCTION test_reject_shadow_checkpoint()
                """);
        try {
            assertThatThrownBy(() -> rebuild.step(run, 100)).isInstanceOf(RuntimeException.class);
            assertThat(shadowCount(run, "consumed_payment_events")).isZero();
            assertThat(shadowCount(run, "payment_event_projections")).isZero();
            assertThat(image("projection_rebuild_runs", "projection_rebuild_generations", "projection_rebuild_items"))
                    .isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_shadow_checkpoint ON projection_rebuild_items");
            jdbc.execute("DROP FUNCTION test_reject_shadow_checkpoint()");
        }
        rebuild.step(run, 100);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(1);
    }

    @Test
    void runIdentityAndFrozenManifestCannotBeRewrittenOrDeleted() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "immutable build evidence");
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_runs SET operator = 'rewritten' WHERE run_id = ?", run))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_generations SET reason = 'rewritten' WHERE run_id = ?", run))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_rebuild_items SET source_version = source_version + 1 WHERE run_id = ?", run))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projection_rebuild_items WHERE run_id = ?", run))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsAppliedCheckpointWithoutTheDurableMatchingReceipt() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "checkpoint requires receipt");
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    jdbc.update("UPDATE projection_rebuild_items SET status = 'APPLIED', applied_at = clock_timestamp() WHERE run_id = ?", run);
                    jdbc.update("UPDATE projection_rebuild_generations SET state = 'BUILT' WHERE run_id = ?", run);
                })).isInstanceOf(RuntimeException.class);
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
        assertThat(rebuild.status(run).appliedItems()).isZero();
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
    }

    @Test
    void databaseRejectsAppliedCheckpointWithOnlyAReceiptAndNoProjection() throws Exception {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "projection must accompany receipt");
        var event = sourceEvent(payment, 0);
        receipts.insertReceipt(shadow(run), event, mapper.writeValueAsString(event));
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    jdbc.update("UPDATE projection_rebuild_items SET status = 'APPLIED', applied_at = clock_timestamp() WHERE run_id = ?", run);
                    jdbc.update("UPDATE projection_rebuild_generations SET state = 'BUILT' WHERE run_id = ?", run);
                })).isInstanceOf(RuntimeException.class);
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
        assertThat(rebuild.status(run).appliedItems()).isZero();
        assertThat(shadowCount(run, "payment_event_projections")).isZero();
    }

    @Test
    void lastAppliedItemCannotCommitWithoutFinalizingItsGenerationState() throws Exception {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "require terminal state with final checkpoint");
        var event = sourceEvent(payment, 0);
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    receipts.insertReceipt(shadow(run), event, mapper.valueToTree(event).toString());
                    receipts.applyProjection(shadow(run), event);
                    jdbc.update("""
                            UPDATE projection_rebuild_items SET status = 'APPLIED', applied_at = clock_timestamp()
                            WHERE run_id = ? AND generation = 0
                            """, run);
                    // Deliberately omit finishStep: receipt/projection alone cannot certify progress.
                })).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Shadow generation state must finalize with its last applied item");
        assertThat(rebuild.status(run).state()).isEqualTo("PLANNED");
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
        assertThat(shadowCount(run, "consumed_payment_events")).isZero();
        assertThat(shadowCount(run, "payment_event_projections")).isZero();
        rebuild.step(run, 1);
        assertThat(rebuild.status(run).state()).isEqualTo("BUILT");
    }

    @Test
    void unfinishedManifestValidationCannotCommitUnderAPlannedGenerationHeader() {
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    jdbc.update("""
                            INSERT INTO projection_rebuild_runs (run_id,consumer_name,source_name,topic,operator,reason)
                            VALUES (?, ?, 'primary','payment-events.v1','operator','incomplete raw manifest')
                            """, run, shadow(run));
                    jdbc.update("""
                            INSERT INTO projection_rebuild_generations
                                (run_id,generation,request_id,operator,reason,gap_configured_quarantines,
                                 gap_configured_failures,gap_other_quarantines,gap_other_failures)
                            VALUES (?,0,?,'operator','unfinished validation',0,0,0,0)
                            """, run, run);
                    jdbc.update("""
                            INSERT INTO projection_rebuild_items
                                (run_id,generation,payment_id,source_version,source_snapshot,source_matches,history_contiguous)
                            VALUES (?,0,?,0,'{}'::jsonb,false,false)
                            """, run, UUID.randomUUID());
                    jdbc.update("""
                            UPDATE projection_rebuild_generations
                            SET state = 'PLANNED', total_items = 1, history_incomplete_items = 1 WHERE run_id = ?
                            """, run);
                })).isInstanceOf(RuntimeException.class);
        assertThat(count("projection_rebuild_runs")).isZero();
        assertThat(count("projection_rebuild_generations")).isZero();
        assertThat(count("projection_rebuild_items")).isZero();
    }

    @Test
    void existingReceiptWithMissingShadowProjectionFailsClosedOnCatchUpStep() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "initial valid shadow");
        rebuild.step(run, 100);
        jdbc.update("DELETE FROM payment_event_projections WHERE consumer_name = ? AND payment_id = ?", shadow(run), payment);
        assertThat(rebuild.validate(run).status()).isEqualTo("SHADOW_MISMATCH");
        rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "same event after view loss");
        var before = image("consumed_payment_events", "payment_event_projections", "projection_rebuild_items");
        assertThatThrownBy(() -> rebuild.step(run, 100)).isInstanceOf(ShadowRebuildConflictException.class);
        assertThat(image("consumed_payment_events", "payment_event_projections", "projection_rebuild_items")).isEqualTo(before);
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
    }

    @Test
    void equalVersionShadowBusinessDriftFailsClosedOnCatchUpStep() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "initial valid shadow");
        rebuild.step(run, 100);
        jdbc.update("UPDATE payment_event_projections SET amount = 77.00 WHERE consumer_name = ? AND payment_id = ?", shadow(run), payment);
        assertThat(rebuild.validate(run).status()).isEqualTo("SHADOW_MISMATCH");
        rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "same version after drift");
        assertThatThrownBy(() -> rebuild.step(run, 100)).isInstanceOf(ShadowRebuildConflictException.class);
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_event_projections WHERE consumer_name = ?", BigDecimal.class,
                shadow(run))).isEqualByComparingTo("77.00");
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
    }

    @Test
    void acceptedCatchUpRequestReplaysOriginalGenerationEvenAfterALaterGeneration() {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        var originalPlan = rebuild.create(run, "operator", "initial approved build");
        rebuild.step(run, 100);
        payments.authorizePayment(payment);
        UUID request = UUID.randomUUID();
        var firstCatchUp = rebuild.catchUp(run, 0, request, "operator", "authorization generation");
        rebuild.step(run, 100);
        payments.capturePayment(payment);
        rebuild.catchUp(run, 1, UUID.randomUUID(), "operator", "capture generation");
        rebuild.step(run, 100);

        var replay = rebuild.catchUp(run, 0, request, "operator", "authorization generation");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.generation()).isEqualTo(1);
        assertThat(replay.plannedAt()).isEqualTo(firstCatchUp.plannedAt());
        var initialReplay = rebuild.create(run, "operator", "initial approved build");
        assertThat(initialReplay.replayed()).isTrue();
        assertThat(initialReplay.generation()).isZero();
        assertThat(initialReplay.plannedAt()).isEqualTo(originalPlan.plannedAt());
        assertThat(rebuild.status(run).generation()).isEqualTo(2);
        assertThat(count("projection_rebuild_generations")).isEqualTo(3);
    }

    @Test
    void frozenOlderInputCannotRegressAnUnexpectedNewerShadowProjection() throws Exception {
        UUID payment = createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "version guard");
        var newer = event(payment, 2, payments.getPayment(payment));
        receipts.insertReceipt(shadow(run), newer, mapper.writeValueAsString(newer));
        receipts.applyProjection(shadow(run), newer);
        assertThatThrownBy(() -> rebuild.step(run, 100)).isInstanceOf(ShadowRebuildConflictException.class);
        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE consumer_name = ?",
                Long.class, shadow(run))).isEqualTo(2);
        assertThat(rebuild.status(run).pendingItems()).isEqualTo(1);
    }

    @Test
    void concurrentBoundedStepsSerializeAndDoNotDuplicateReceipts() throws Exception {
        createPayment();
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "concurrent workers");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> { start.await(); rebuild.step(run, 1); return true; };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(shadowCount(run, "consumed_payment_events")).isEqualTo(2);
        assertThat(shadowCount(run, "payment_event_projections")).isEqualTo(2);
    }

    @Test
    void competingCatchUpCommandsWithSameExpectedGenerationHaveOneWinner() throws Exception {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "concurrent catch-up setup");
        rebuild.step(run, 100);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try { rebuild.catchUp(run, 0, UUID.randomUUID(), "operator", "catch up"); return true; }
                catch (ShadowRebuildConflictException conflict) { return false; }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("projection_rebuild_generations")).isEqualTo(2);
    }

    @Test
    void repeatedIdenticalCatchUpRequestHasOneDurableGeneration() throws Exception {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "concurrent replay setup");
        rebuild.step(run, 100);
        UUID request = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                start.await(); rebuild.catchUp(run, 0, request, "operator", "same exact catch-up"); return true;
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("projection_rebuild_generations")).isEqualTo(2);
    }

    @Test
    void readOnlyValidationDoesNotChangeSourceShadowOrRecoveryData() {
        createPayment();
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "validate in isolation");
        rebuild.step(run, 100);
        var before = image("payments", "payment_outbox", "consumed_payment_events", "payment_event_projections",
                "consumer_event_failures", "consumer_recovery_audit", "projection_rebuild_runs",
                "projection_rebuild_generations", "projection_rebuild_items");
        rebuild.validate(run);
        assertThat(image("payments", "payment_outbox", "consumed_payment_events", "payment_event_projections",
                "consumer_event_failures", "consumer_recovery_audit", "projection_rebuild_runs",
                "projection_rebuild_generations", "projection_rebuild_items")).isEqualTo(before);
    }

    @Test
    void buildingShadowDoesNotResolveAnExistingPhysicalConsumerIncident() {
        createPayment();
        var bad = new ConsumerRecord<String, String>("payment-events.v1", 0, 0, "private-key", "private malformed value");
        failures.failed(bad, new InvalidPaymentEventException("invalid original"));
        var before = image("consumer_event_positions", "consumer_event_failures", "consumer_recovery_audit");
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "read model does not close physical incident");
        rebuild.step(run, 100);
        var validation = rebuild.validate(run);
        assertThat(validation.status()).isEqualTo("MATCHES_CURRENT_SOURCE_WITH_OPEN_GAPS");
        assertThat(validation.recoveryGaps().configuredOtherOpenFailures()).isEqualTo(1);
        assertThat(image("consumer_event_positions", "consumer_event_failures", "consumer_recovery_audit")).isEqualTo(before);
    }

    @Test
    void missingRunsAndUnboundedOrAmbiguousServiceArgumentsAreRejected() {
        assertThatThrownBy(() -> rebuild.status(UUID.randomUUID())).isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThatThrownBy(() -> rebuild.step(UUID.randomUUID(), 50)).isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThatThrownBy(() -> rebuild.validate(UUID.randomUUID())).isInstanceOf(ShadowRebuildNotFoundException.class);
        assertThatThrownBy(() -> rebuild.create(null, "operator", "missing id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rebuild.create(UUID.randomUUID(), "operator", " ")).isInstanceOf(IllegalArgumentException.class);
        UUID run = UUID.randomUUID();
        rebuild.create(run, "operator", "validation setup");
        assertThatThrownBy(() -> rebuild.step(run, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rebuild.step(run, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rebuild.catchUp(run, -1, UUID.randomUUID(), "operator", "invalid generation"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID createPayment() {
        return payments.createPayment("shadow-" + UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-REFERENCE", BigDecimal.TEN, "INR")).payment().id();
    }

    private boolean createAttempt(UUID run, String reason, CountDownLatch start) throws InterruptedException {
        start.await();
        try { rebuild.create(run, "operator", reason); return true; }
        catch (com.fincore.payments.exception.ShadowRebuildConflictException conflict) { return false; }
    }

    private void installPlanningFailure() {
        jdbc.execute("""
                CREATE FUNCTION test_reject_shadow_planning() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated manifest classification failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_shadow_planning BEFORE UPDATE ON projection_rebuild_items
                FOR EACH ROW WHEN (NEW.status = 'PENDING') EXECUTE FUNCTION test_reject_shadow_planning()
                """);
    }

    private void removePlanningFailure() {
        jdbc.execute("DROP TRIGGER test_reject_shadow_planning ON projection_rebuild_items");
        jdbc.execute("DROP FUNCTION test_reject_shadow_planning()");
    }

    private String shadow(UUID run) { return "shadow-" + run; }

    private long shadowCount(UUID run, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE consumer_name = ?", Long.class, shadow(run));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private JsonNode json(Object value) { return mapper.valueToTree(value); }

    private PaymentEvent sourceEvent(UUID payment, long version) throws Exception {
        return mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id = ? AND payment_version = ?",
                String.class, payment, version), PaymentEvent.class);
    }

    private PaymentEvent event(UUID id, long version, PaymentResponse data) {
        return new PaymentEvent(UUID.randomUUID(), PaymentEventType.from(data.status()), 1, id, version,
                data.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS), data);
    }

    private List<String> sourceAndLiveImage() {
        var image = image("payments", "idempotency_records", "ledger_accounts", "ledger_transactions", "ledger_entries",
                "payment_outbox", "local_payment_event_inbox", "consumer_event_positions", "consumer_event_failures",
                "consumer_recovery_audit", "outbox_requeue_audit");
        for (String table : List.of("consumed_payment_events", "payment_event_projections")) {
            image.add(jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM "
                    + table + " t WHERE consumer_name = ?", String.class, PaymentEventConsumer.CONSUMER_NAME));
        }
        return image;
    }

    private List<String> image(String... tables) {
        var image = new ArrayList<String>();
        for (String table : tables) {
            image.add(jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM "
                    + table + " t", String.class));
        }
        return image;
    }
}
