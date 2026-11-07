package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.ProjectionOperationsProperties;
import com.fincore.payments.dto.*;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
abstract class ProjectionEvidenceDatabaseTests {
    @Autowired ProjectionEvidenceService evidence;
    @MockitoSpyBean ProjectionEvidenceRepository evidenceRepository;
    @Autowired ProjectionRoutingService routing;
    @Autowired ProjectionRoutingRepository routes;
    @Autowired ProjectionRebuildService rebuild;
    @Autowired ProjectionRebuildRepository plans;
    @Autowired ProjectionReconciliationRepository reconciliation;
    @Autowired PaymentEventConsumerRepository receipts;
    @Autowired PaymentEventConsumer consumer;
    @Autowired PaymentService payments;
    @Autowired PaymentEventRecordDecoder decoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProjectionOperationsProperties properties;
    @Autowired KafkaEventProperties kafka;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans, projection_routes,projection_route_audit,projection_rebuild_items,
                    projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,
                    consumer_event_failures,consumer_event_positions,outbox_requeue_audit,payment_event_projections,
                    consumed_payment_events,local_payment_event_inbox,payment_outbox,ledger_entries,
                    ledger_transactions,ledger_accounts,idempotency_records,payments
                """);
    }

    @Test
    void emptyDatabaseReturnsReviewPolicyWithoutInventingDeletionAuthority() {
        var report=evidence.review(null,50);
        assertThat(report.targets()).isEmpty();
        assertThat(report.routeRevision()).isZero();
        assertThat(report.hasMore()).isFalse();
        assertThat(report.nextAfterRunId()).isNull();
        assertThat(report.assessment()).isEqualTo("REVIEW_ONLY_NO_DELETION_AUTHORITY");
        assertThat(report.preservationRequirements()).contains("BASELINE_SOURCE_AND_RECOVERY_EVIDENCE");
    }

    @Test
    void inventoryCountsAllGenerationsAndExactLogicalTextBytesWithoutExportingPayloads() throws Exception {
        UUID payment=payments.createPayment("unicode-" + UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-\u20b9-\ud83d\ude00",BigDecimal.TEN,"INR")).payment().id();
        consumer.consume(event(payment,0));
        UUID run=build();
        payments.authorizePayment(payment);
        consumer.consume(event(payment,1));
        rebuild.catchUp(run,0,UUID.randomUUID(),"builder","second frozen generation");
        rebuild.step(run,100);
        var report=evidence.review(null,50);
        var target=report.targets().getFirst();
        assertThat(target.runId()).isEqualTo(run);
        assertThat(target.lifecycle()).isEqualTo("ISOLATED");
        assertThat(target.reviewState()).isEqualTo("RETAIN_ISOLATED");
        assertThat(target.deletionPermitted()).isFalse();
        assertThat(target.artifacts().generations()).isEqualTo(2);
        assertThat(target.artifacts().manifestItems()).isEqualTo(2);
        assertThat(target.artifacts().receipts()).isEqualTo(2);
        assertThat(target.artifacts().projections()).isEqualTo(1);
        assertThat(target.artifacts().routingDecisions()).isZero();
        List<String> texts=jdbc.queryForList("""
                SELECT payload FROM consumed_payment_events WHERE consumer_name=?
                UNION ALL SELECT original_payload FROM projection_rebuild_items WHERE run_id=?
                UNION ALL SELECT canonical_payload FROM projection_rebuild_items WHERE run_id=?
                """,String.class,"shadow-" + run,run,run);
        long byteCount=texts.stream().mapToLong(t -> t.getBytes(StandardCharsets.UTF_8).length).sum();
        assertThat(target.artifacts().retainedTextBytes()).isEqualTo(BigInteger.valueOf(byteCount));
        assertThat(byteCount).isGreaterThan(texts.stream().mapToLong(String::length).sum());
        assertThat(mapper.writeValueAsString(report)).doesNotContain("PRIVATE-", "second frozen generation", "builder");
    }

    @Test
    void retiredAtIsTheDepartingDecisionEvenAfterLaterTargetChanges() throws Exception {
        createAndConsume();
        UUID first=build();
        UUID next=build();
        routing.promote(promotion(first,0),"operator");
        routing.rollback(rollback(1),"operator");
        var replacement=routing.replace(new ProjectionReplacementCommand(UUID.randomUUID(),first,next,0,2,"replace"),"operator");
        routing.rollback(rollback(3),"operator");
        var retired=routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),next,4,"retire"),"operator");
        var items=evidence.review(null,100).targets();
        var a=items.stream().filter(t -> t.runId().equals(first)).findFirst().orElseThrow();
        var b=items.stream().filter(t -> t.runId().equals(next)).findFirst().orElseThrow();
        assertThat(a.retirementRevision()).isEqualTo(3);
        assertThat(a.retiredAt()).isEqualTo(replacement.occurredAt());
        assertThat(a.artifacts().routingDecisions()).isEqualTo(3);
        assertThat(b.retirementRevision()).isEqualTo(5);
        assertThat(b.retiredAt()).isEqualTo(retired.occurredAt());
        assertThat(b.artifacts().routingDecisions()).isEqualTo(3);
        assertThat(items).allSatisfy(t -> {
            assertThat(t.lifecycle()).isEqualTo("RETIRED");
            assertThat(t.reviewState()).isEqualTo("RETAIN_REVIEW_WINDOW");
            assertThat(t.deletionPermitted()).isFalse();
        });
    }

    @Test
    void currentMaintenanceIsRetainedWhileBaselineServesReads() throws Exception {
        createAndConsume();
        UUID run=build();
        routing.promote(promotion(run,0),"operator");
        routing.rollback(rollback(1),"operator");
        var report=evidence.review(null,100);
        assertThat(report.routeRevision()).isEqualTo(2);
        var target=report.targets().getFirst();
        assertThat(target.lifecycle()).isEqualTo("MAINTAINED");
        assertThat(target.reviewState()).isEqualTo("RETAIN_MAINTAINED");
        assertThat(target.retiredAt()).isNull();
        assertThat(target.retirementRevision()).isNull();
    }

    @Test
    void keysetPagesUseAnExtraRowAndDoNotInventAFinalCursor() {
        for (int i=1;i<=3;i++) rebuild.create(new UUID(0,i),"builder","ordered isolated target");
        var first=evidence.review(null,1);
        assertThat(first.targets()).extracting(ProjectionEvidenceReport.TargetEvidence::runId).containsExactly(new UUID(0,1));
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextAfterRunId()).isEqualTo(new UUID(0,1));
        var second=evidence.review(first.nextAfterRunId(),1);
        assertThat(second.targets()).extracting(ProjectionEvidenceReport.TargetEvidence::runId).containsExactly(new UUID(0,2));
        var last=evidence.review(second.nextAfterRunId(),1);
        assertThat(last.targets()).extracting(ProjectionEvidenceReport.TargetEvidence::runId).containsExactly(new UUID(0,3));
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextAfterRunId()).isNull();
        assertThat(evidence.review(new UUID(0,99),1).targets()).isEmpty();
    }

    @Test
    void reportExcludesOtherSourceNamespacesAndTopics() {
        UUID local=build();
        new ProjectionRebuildService(plans,receipts,reconciliation,decoder,mapper,kafka,"other-source",transactions)
                .create(UUID.randomUUID(),"builder","other source target");
        var otherTopic=new KafkaEventProperties("other-topic",kafka.consumerGroup(),kafka.sendTimeoutMs());
        new ProjectionRebuildService(plans,receipts,reconciliation,decoder,mapper,otherTopic,"primary",transactions)
                .create(UUID.randomUUID(),"builder","other topic target");
        assertThat(evidence.review(null,100).targets()).extracting(ProjectionEvidenceReport.TargetEvidence::runId)
                .containsExactly(local);
    }

    @Test
    void retentionReviewLeavesEveryApplicationTableUnchanged() throws Exception {
        createAndConsume();
        UUID run=build();
        routing.promote(promotion(run,0),"operator");
        routing.rollback(rollback(1),"operator");
        routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),run,2,"retain evidence"),"operator");
        Map<String,String> before=databaseImage();
        evidence.review(null,100);
        evidence.review(run,1);
        assertThat(databaseImage()).isEqualTo(before);
    }

    @Test
    void readOnlyReportUsesOneSnapshotWhileRetirementCanCommitConcurrently() throws Exception {
        createAndConsume();
        UUID run=build();
        routing.promote(promotion(run,0),"operator");
        routing.rollback(rollback(1),"operator");
        var observed=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        doAnswer(call -> {
            Object result=call.callRealMethod();
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            observed.countDown();
            if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("report release timed out");
            return result;
        }).when(evidenceRepository).observationTime();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var report=executor.submit(() -> evidence.review(null,100));
            try {
                assertThat(observed.await(10,TimeUnit.SECONDS)).isTrue();
                routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),run,2,"concurrent retirement"),"operator");
            } finally { release.countDown(); }
            var old=report.get(10,TimeUnit.SECONDS);
            assertThat(old.routeRevision()).isEqualTo(2);
            assertThat(old.targets().getFirst().lifecycle()).isEqualTo("MAINTAINED");
            assertThat(old.targets().getFirst().retiredAt()).isNull();
        } finally { doCallRealMethod().when(evidenceRepository).observationTime(); }
        var current=evidence.review(null,100);
        assertThat(current.routeRevision()).isEqualTo(3);
        assertThat(current.targets().getFirst().lifecycle()).isEqualTo("RETIRED");
    }

    private Map<String,String> databaseImage() {
        var image=new java.util.LinkedHashMap<String,String>();
        for (String table:List.of("payments","idempotency_records","ledger_accounts","ledger_transactions","ledger_entries",
                "payment_outbox","local_payment_event_inbox","consumed_payment_events","payment_event_projections",
                "outbox_requeue_audit","consumer_event_positions","consumer_event_failures","consumer_recovery_audit",
                "projection_rebuild_runs","projection_rebuild_generations","projection_rebuild_items","projection_routes","projection_route_audit")) {
            image.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM " + table + " t",String.class));
        }
        return image;
    }

    private UUID createAndConsume() throws Exception {
        UUID payment=payments.createPayment("evidence-" + UUID.randomUUID(),new CreatePaymentRequest("PRIVATE-EVIDENCE",BigDecimal.TEN,"INR"))
                .payment().id();
        consumer.consume(event(payment,0));
        return payment;
    }
    private UUID build() {
        UUID run=UUID.randomUUID();
        rebuild.create(run,"builder","evidence target");
        rebuild.step(run,100);
        return run;
    }
    private PaymentEvent event(UUID payment,long version) throws Exception {
        return mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id=? AND payment_version=?",
                String.class,payment,version),PaymentEvent.class);
    }
    private ProjectionPromotionCommand promotion(UUID run,long revision) {
        return new ProjectionPromotionCommand(UUID.randomUUID(),run,0,revision,"promote");
    }
    private ProjectionRollbackCommand rollback(long revision) {
        return new ProjectionRollbackCommand(UUID.randomUUID(),revision,"rollback");
    }
}
