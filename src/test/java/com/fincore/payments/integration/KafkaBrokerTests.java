package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.KafkaLagStatus;
import com.fincore.payments.dto.PaymentResponse;
import com.fincore.payments.repository.OutboxRepository;
import com.fincore.payments.service.KafkaLagService;
import com.fincore.payments.service.OutboxDeliveryWorker;
import com.fincore.payments.service.OutboxDispatcher;
import com.fincore.payments.service.PaymentEventConsumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.fincore.payments.support.MerchantMvcRequests.get;
import static com.fincore.payments.support.MerchantMvcRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises real broker acknowledgement, PostgreSQL commits, offset commits, and duplicate delivery. */
@SpringBootTest(properties = {
        "fincore.events.transport=kafka",
        "fincore.outbox.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class KafkaBrokerTests {
    private static final String TOPIC = "payment-events-it-" + UUID.randomUUID();
    private static final String CONSUMER_GROUP = "fincore-broker-it-" + UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxDeliveryWorker worker;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired Environment environment;
    @Autowired KafkaLagService lag;
    @Autowired com.fincore.payments.service.ConsumerRecoveryService recovery;
    @Autowired com.fincore.payments.service.ProjectionReconciliationService reconciliation;
    @Autowired com.fincore.payments.service.ProjectionRebuildService rebuild;
    @Autowired com.fincore.payments.service.ProjectionRoutingService routing;
    @MockitoSpyBean com.fincore.payments.service.PaymentEventRecordDecoder decoder;
    @MockitoSpyBean OutboxRepository outbox;
    @MockitoSpyBean PaymentEventConsumer consumer;
    private OutboxRepository outboxSpy;

    @DynamicPropertySource
    static void isolatedConsumerGroup(DynamicPropertyRegistry registry) {
        registry.add("fincore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("fincore.kafka.topic", () -> TOPIC);
    }

    @BeforeEach
    void resetDisposableDatabase() {
        outboxSpy = AopTestUtils.getUltimateTargetObject(outbox);
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes, projection_route_audit, projection_rebuild_items, projection_rebuild_generations, projection_rebuild_runs, consumer_recovery_audit, consumer_event_failures, consumer_event_positions, outbox_requeue_audit,
                    payment_event_projections, consumed_payment_events,
                    local_payment_event_inbox, payment_outbox, ledger_entries,
                    ledger_transactions, ledger_accounts, idempotency_records, payments
                """);
    }

    @Test
    void httpLifecyclePublishesThroughKafkaAndBuildsTheConsumerProjection() throws Exception {
        UUID paymentId = capturedPayment("broker-lifecycle");

        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);

        awaitProjection(paymentId, 2);
        awaitAllPublishedRecordsConsumed();
        assertSuccessfulCapturedDelivery(paymentId);
        var report = reconciliation.reconcile(paymentId, null, 50);
        assertThat(report.payments().getFirst().findings()).isEmpty();
        assertThat(report.payments().getFirst().matchingReceipts()).isEqualTo(3);
        assertThat(report.payments().getFirst().latestSourceReplayCandidate()).isTrue();
        UUID shadowRun = UUID.randomUUID();
        rebuild.create(shadowRun, "broker-test", "verify isolated rebuild beside live Kafka consumer");
        rebuild.step(shadowRun, 1);
        assertThat(rebuild.validate(shadowRun).status()).isEqualTo("MATCHES_CURRENT_SOURCE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE consumer_name = ?",
                Long.class, "shadow-" + shadowRun)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE consumer_name = ?",
                Long.class, PaymentEventConsumer.CONSUMER_NAME)).isEqualTo(3);
        mvc.perform(get("/api/v1/payments/{id}/projection", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentVersion").value(2))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        routing.promote(new com.fincore.payments.dto.ProjectionPromotionCommand(UUID.randomUUID(),shadowRun,
                0,0,"verified Kafka view promotion"),"broker-test");
        mvc.perform(post("/api/v1/payments/{id}/refund",paymentId)).andExpect(status().isOk());
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM payment_event_projections WHERE payment_id=? AND payment_version=3
                        """,Long.class,paymentId)).isEqualTo(2));
        awaitAllPublishedRecordsConsumed();
        mvc.perform(get("/api/v1/payments/{id}/projection",paymentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        routing.rollback(new com.fincore.payments.dto.ProjectionRollbackCommand(UUID.randomUUID(),1,
                "verified maintained baseline rollback"),"broker-test");
        mvc.perform(get("/api/v1/payments/{id}/projection",paymentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentVersion").value(3))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        UUID replacement=UUID.randomUUID();
        rebuild.create(replacement,"broker-test","fresh replacement after baseline rollback");
        rebuild.step(replacement,100);
        routing.replace(new com.fincore.payments.dto.ProjectionReplacementCommand(UUID.randomUUID(),shadowRun,
                replacement,0,2,"transfer live maintenance"),"broker-test");
        UUID later=capturedPayment("broker-after-replacement");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections WHERE payment_id=? AND payment_version=2",
                        Long.class,later)).isEqualTo(2));
        awaitAllPublishedRecordsConsumed();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections WHERE payment_id=? AND consumer_name=?",
                Long.class,later,"shadow-" + shadowRun)).isZero();
        routing.rollback(new com.fincore.payments.dto.ProjectionRollbackCommand(UUID.randomUUID(),3,"baseline before retirement"),"broker-test");
        routing.retire(new com.fincore.payments.dto.ProjectionRetirementCommand(UUID.randomUUID(),replacement,4,
                "retain target evidence without further fanout"),"broker-test");
        mvc.perform(post("/api/v1/payments/{id}/refund",later)).andExpect(status().isOk());
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE payment_id=? AND consumer_name=?",
                        Long.class,later,PaymentEventConsumer.CONSUMER_NAME)).isEqualTo(3));
        awaitAllPublishedRecordsConsumed();
        assertThat(jdbc.queryForObject("SELECT payment_version FROM payment_event_projections WHERE payment_id=? AND consumer_name=?",
                Long.class,later,"shadow-" + replacement)).isEqualTo(2);
        assertThat(routing.target(shadowRun).lifecycle()).isEqualTo("RETIRED");
        assertThat(routing.target(replacement).lifecycle()).isEqualTo("RETIRED");
    }

    @Test
    void redeliveredBrokerRecordCommitsItsOffsetWithoutRepeatingTheDatabaseEffect() throws Exception {
        UUID paymentId = capturedPayment("broker-redelivery");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);
        awaitProjection(paymentId, 2);
        awaitAllPublishedRecordsConsumed();
        var originalProjection = jdbc.queryForMap("SELECT * FROM payment_event_projections WHERE payment_id = ?", paymentId);
        String originalPayload = jdbc.queryForObject("""
                SELECT payload FROM payment_outbox WHERE payment_id = ? AND payment_version = 2
                """, String.class, paymentId);

        var duplicate = template.send(TOPIC, paymentId.toString(), originalPayload).get(15, TimeUnit.SECONDS);
        awaitCommitted(new TopicPartition(TOPIC, duplicate.getRecordMetadata().partition()),
                duplicate.getRecordMetadata().offset() + 1);

        assertSuccessfulCapturedDelivery(paymentId);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_event_projections WHERE payment_id = ?", paymentId))
                .isEqualTo(originalProjection);
    }

    @Test
    void brokerSuccessBeforeOutboxRollbackIsSafelyRedeliveredWithTheSameEventId() throws Exception {
        UUID paymentId = capturedPayment("broker-crash-window");
        UUID firstEventId = jdbc.queryForObject("""
                SELECT event_id FROM payment_outbox WHERE payment_id = ? AND payment_version = 0
                """, UUID.class, paymentId);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Simulated crash after broker acknowledgement before outbox commit");
        }).when(outboxSpy).markDelivered(any(UUID.class));
        try {
            assertThatThrownBy(() -> worker.dispatchNext())
                    // @Repository exception translation can wrap the injected IllegalStateException.
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated crash");
        } finally {
            doCallRealMethod().when(outboxSpy).markDelivered(any(UUID.class));
        }

        awaitProjection(paymentId, 0);
        awaitAllPublishedRecordsConsumed();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE event_id = ?", Long.class,
                firstEventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox WHERE event_id = ?", String.class, firstEventId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM payment_outbox WHERE event_id = ?", Integer.class, firstEventId))
                .isZero();

        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);
        awaitProjection(paymentId, 2);
        awaitAllPublishedRecordsConsumed();

        assertSuccessfulCapturedDelivery(paymentId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE event_id = ?", Long.class,
                firstEventId)).isEqualTo(1);
    }

    @Test
    void failedConsumerTransactionRetainsItsOffsetAndRetriesBeforeAcknowledging() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-01T10:00:00Z");
        var data = new PaymentResponse(paymentId, "BROKER-CONSUMER-ROLLBACK", new BigDecimal("10.00"),
                "INR", PaymentStatus.CREATED, occurredAt, occurredAt);
        var event = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1,
                paymentId, 0, occurredAt, data);
        PaymentEventConsumer consumerSpy = AopTestUtils.getUltimateTargetObject(consumer);
        var attempts = new AtomicInteger();
        var secondAttemptStarted = new CountDownLatch(1);
        var releaseSecondAttempt = new CountDownLatch(1);
        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                invocation.callRealMethod();
                throw new IllegalStateException("Simulated failure after consumer writes before database commit");
            }
            if (attempt == 2) {
                secondAttemptStarted.countDown();
                if (!releaseSecondAttempt.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the second consumer attempt");
                }
            }
            return invocation.callRealMethod();
        }).when(consumerSpy).consume(any(PaymentEvent.class));

        try {
            var sent = template.send(TOPIC, paymentId.toString(), mapper.writeValueAsString(event))
                    .get(15, TimeUnit.SECONDS).getRecordMetadata();
            var partition = new TopicPartition(TOPIC, sent.partition());
            assertThat(secondAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // The first attempt has escaped the service transaction proxy and rolled back. The
            // second attempt is paused before doing any work, so a premature offset is observable.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections", Long.class)).isZero();
            var openFailure = jdbc.queryForMap("""
                    SELECT * FROM consumer_event_failures
                    WHERE topic = ? AND record_partition = ? AND record_offset = ?
                    """, TOPIC, sent.partition(), sent.offset());
            assertThat(openFailure).containsEntry("consumer_name", PaymentEventConsumer.CONSUMER_NAME)
                    .containsEntry("consumer_group", CONSUMER_GROUP)
                    .containsEntry("error_code", "CONSUMER_FAILED")
                    .containsEntry("attempts", 1)
                    .containsEntry("record_key", paymentId.toString())
                    .containsEntry("record_value", mapper.writeValueAsString(event))
                    .containsEntry("resolved_at", null);
            try (var admin = admin()) {
                var committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(partition);
                if (committed != null) assertThat(committed.offset()).isLessThanOrEqualTo(sent.offset());
            }

            releaseSecondAttempt.countDown();
            awaitProjection(paymentId, 0);
            awaitCommitted(partition, sent.offset() + 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isZero();
            var resolvedFailure = jdbc.queryForMap("""
                    SELECT * FROM consumer_event_failures
                    WHERE topic = ? AND record_partition = ? AND record_offset = ?
                    """, TOPIC, sent.partition(), sent.offset());
            assertThat(resolvedFailure).containsEntry("failure_id", openFailure.get("failure_id"))
                    .containsEntry("first_failed_at", openFailure.get("first_failed_at"))
                    .containsEntry("attempts", 1);
            assertThat(resolvedFailure.get("resolved_at")).isNotNull();
        } finally {
            releaseSecondAttempt.countDown();
            doCallRealMethod().when(consumerSpy).consume(any(PaymentEvent.class));
        }
    }

    @Test
    void lagReportShowsKnownZeroOnlyAfterEveryPartitionHasCommittedItsRecords() throws Exception {
        // Every partition gets a record; an untouched partition with no committed offset is
        // deliberately UNINITIALIZED, even if its end offset is zero.
        int partitionCount;
        try (var admin = admin()) {
            var description = admin.describeTopics(List.of(TOPIC)).allTopicNames()
                    .get(5, TimeUnit.SECONDS).get(TOPIC);
            partitionCount = description.partitions().size();
            for (var partition : description.partitions()) {
                UUID paymentId = UUID.randomUUID();
                Instant occurredAt = Instant.parse("2026-10-01T10:00:00Z");
                var data = new PaymentResponse(paymentId, "BROKER-LAG-" + partition.partition(),
                        new BigDecimal("10.00"), "INR", PaymentStatus.CREATED, occurredAt, occurredAt);
                var event = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1,
                        paymentId, 0, occurredAt, data);
                template.send(TOPIC, partition.partition(), paymentId.toString(), mapper.writeValueAsString(event))
                        .get(15, TimeUnit.SECONDS);
            }
        }
        awaitAllPublishedRecordsConsumed();

        var report = lag.report();

        assertThat(report.topic()).isEqualTo(TOPIC);
        assertThat(report.consumerGroup()).isEqualTo(CONSUMER_GROUP);
        assertThat(report.status()).isEqualTo(KafkaLagStatus.KNOWN);
        assertThat(report.totalLag()).isZero();
        assertThat(report.errorCode()).isNull();
        assertThat(report.partitions()).hasSize(partitionCount).allSatisfy(partition -> {
            assertThat(partition.status()).isEqualTo(KafkaLagStatus.KNOWN);
            assertThat(partition.lag()).isZero();
            assertThat(partition.committedOffset()).isEqualTo(partition.endOffset());
        });
    }

    @Test
    void explicitQuarantineUnblocksPoisonPartitionWhileKeepingItsGapAndEvidence() throws Exception {
        var poison = template.send(TOPIC, 0, "private-key", "private malformed JSON")
                .get(15, TimeUnit.SECONDS).getRecordMetadata();
        UUID paymentId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        var event = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1, paymentId, 0, at,
                new PaymentResponse(paymentId, "after-poison", new BigDecimal("10.00"), "INR", PaymentStatus.CREATED, at, at));
        var following = template.send(TOPIC, 0, paymentId.toString(), mapper.writeValueAsString(event))
                .get(15, TimeUnit.SECONDS).getRecordMetadata();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM consumer_event_failures WHERE topic = ? AND record_partition = 0 AND record_offset = ?
                """, Long.class, TOPIC, poison.offset())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections WHERE payment_id = ?",
                Long.class, paymentId)).isZero();
        assertOffsetNotAdvanced(new TopicPartition(TOPIC, 0), poison.offset());
        var command = recoveryCommand(poison.offset());

        recovery.quarantine(command);
        awaitCommitted(new TopicPartition(TOPIC, 0), following.offset() + 1);
        awaitProjection(paymentId, 0);

        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
        assertThat(recovery.gaps().status()).isEqualTo("DEGRADED");
        assertThat(jdbc.queryForObject("SELECT record_value FROM consumer_event_failures WHERE failure_id = ?",
                String.class, command.failureId())).isEqualTo("private malformed JSON");
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM consumer_event_failures WHERE failure_id = ?",
                java.sql.Timestamp.class, command.failureId())).isNull();
        assertThatThrownBy(() -> recovery.replay(new com.fincore.payments.dto.ConsumerRecoveryCommand(UUID.randomUUID(),
                command.failureId(), command.expectedFingerprint(), "broker-test", "still-invalid")))
                .isInstanceOf(com.fincore.payments.exception.InvalidPaymentEventException.class);
        assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
    }

    @Test
    void preservedOriginalCanBeReplayedAfterCompatibleDecoderFixWithoutPublishingAgain() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        var event = new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_CREATED, 1, paymentId, 0, at,
                new PaymentResponse(paymentId, "decoder-fix", new BigDecimal("10.00"), "INR", PaymentStatus.CREATED, at, at));
        com.fincore.payments.service.PaymentEventRecordDecoder decoderSpy = AopTestUtils.getUltimateTargetObject(decoder);
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record = invocation.getArgument(0);
            if (paymentId.toString().equals(record.key()) && reject.get()) {
                throw new com.fincore.payments.exception.InvalidPaymentEventException("simulated older decoder");
            }
            return invocation.callRealMethod();
        }).when(decoderSpy).decode(any());
        try {
            var sent = template.send(TOPIC, 0, paymentId.toString(), mapper.writeValueAsString(event))
                    .get(15, TimeUnit.SECONDS).getRecordMetadata();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM consumer_event_failures WHERE topic = ? AND record_partition = 0 AND record_offset = ?
                    """, Long.class, TOPIC, sent.offset())).isEqualTo(1));
            var command = recoveryCommand(sent.offset());
            recovery.quarantine(command);
            awaitCommitted(new TopicPartition(TOPIC, 0), sent.offset() + 1);
            assertThat(recovery.gaps().openQuarantines()).isEqualTo(1);
            reject.set(false);
            var replay = new com.fincore.payments.dto.ConsumerRecoveryCommand(UUID.randomUUID(), command.failureId(),
                    command.expectedFingerprint(), "broker-test", "compatible decoder deployed");

            recovery.replay(replay);

            awaitProjection(paymentId, 0);
            assertThat(recovery.gaps().status()).isEqualTo("NO_KNOWN_GAPS");
            assertThat(recovery.replay(replay).replayed()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE payment_id = ?",
                    Long.class, paymentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
        } finally {
            reject.set(false);
            doCallRealMethod().when(decoderSpy).decode(any());
        }
    }

    private com.fincore.payments.dto.ConsumerRecoveryCommand recoveryCommand(long offset) {
        var row = jdbc.queryForMap("""
                SELECT f.failure_id, p.wire_fingerprint FROM consumer_event_failures f JOIN consumer_event_positions p
                  USING (source_name, consumer_name, topic, record_partition, record_offset)
                WHERE topic = ? AND record_partition = 0 AND record_offset = ?
                """, TOPIC, offset);
        return new com.fincore.payments.dto.ConsumerRecoveryCommand(UUID.randomUUID(), (UUID) row.get("failure_id"),
                row.get("wire_fingerprint").toString(), "broker-test", "reviewed projection gap");
    }

    private void assertOffsetNotAdvanced(TopicPartition partition, long offset) throws Exception {
        try (var admin = admin()) {
            var committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition);
            if (committed != null) assertThat(committed.offset()).isLessThanOrEqualTo(offset);
        }
    }

    private void awaitProjection(UUID paymentId, long version) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT payment_version FROM payment_event_projections WHERE payment_id = ?",
                        Long.class, paymentId)).containsExactly(version));
    }

    private void awaitAllPublishedRecordsConsumed() throws Exception {
        try (var admin = admin()) {
            var topic = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS).get(TOPIC);
            Map<TopicPartition, OffsetSpec> requested = topic.partitions().stream().collect(Collectors.toMap(
                    partition -> new TopicPartition(TOPIC, partition.partition()), partition -> OffsetSpec.latest()));
            var endOffsets = admin.listOffsets(requested).all().get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                for (var entry : endOffsets.entrySet()) {
                    if (entry.getValue().offset() == 0) continue;
                    assertThat(committed).containsKey(entry.getKey());
                    assertThat(committed.get(entry.getKey()).offset()).isGreaterThanOrEqualTo(entry.getValue().offset());
                }
            });
        }
    }

    private void awaitCommitted(TopicPartition partition, long nextOffset) {
        try (var admin = admin()) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                assertThat(committed).containsKey(partition);
                assertThat(committed.get(partition).offset()).isGreaterThanOrEqualTo(nextOffset);
            });
        }
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, environment.getRequiredProperty("spring.kafka.bootstrap-servers"),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000"));
    }

    private void assertSuccessfulCapturedDelivery(UUID paymentId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE payment_id = ? AND status = 'DELIVERED'",
                Long.class, paymentId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_payment_events WHERE payment_id = ?", Long.class,
                paymentId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_event_projections WHERE payment_id = ?", Long.class,
                paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM local_payment_event_inbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(1);
    }

    private UUID capturedPayment(String reference) throws Exception {
        var created = mvc.perform(post("/api/v1/payments").header("Idempotency-Key", reference)
                        .contentType(APPLICATION_JSON)
                        .content("{\"merchantReference\":\"" + reference + "\",\"amount\":10.00,\"currency\":\"INR\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID paymentId = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/api/v1/payments/{id}/authorize", paymentId)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/{id}/capture", paymentId)).andExpect(status().isOk());
        return paymentId;
    }
}
