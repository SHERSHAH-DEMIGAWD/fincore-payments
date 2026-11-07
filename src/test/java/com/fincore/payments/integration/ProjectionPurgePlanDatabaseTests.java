package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.*;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
abstract class ProjectionPurgePlanDatabaseTests {
    @Autowired ProjectionPurgePlanService plans;
    @Autowired ProjectionPurgePlanRepository repository;
    @MockitoSpyBean ProjectionEvidenceRepository evidence;
    @Autowired ProjectionEvidenceService policy;
    @Autowired ProjectionRoutingService routing;
    @Autowired ProjectionRoutingRepository routes;
    @Autowired ProjectionRebuildService rebuild;
    @Autowired PaymentService payments;
    @Autowired PaymentEventConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaEventProperties kafka;
    @Autowired PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource dataSource;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans,projection_routes,projection_route_audit,projection_rebuild_items,
                    projection_rebuild_generations,projection_rebuild_runs,consumer_recovery_audit,
                    consumer_event_failures,consumer_event_positions,outbox_requeue_audit,payment_event_projections,
                    consumed_payment_events,local_payment_event_inbox,payment_outbox,ledger_entries,
                    ledger_transactions,ledger_accounts,idempotency_records,payments
                """);
        UUID payment=payments.createPayment("plan-"+UUID.randomUUID(),new CreatePaymentRequest("PRIVATE-PLAN",BigDecimal.TEN,"INR")).payment().id();
        consumer.consume(mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id=?",String.class,payment),PaymentEvent.class));
        // Simulate elapsed retention time without weakening or rewriting immutable retirement audits.
        doReturn(Instant.now().plus(31,ChronoUnit.DAYS)).when(evidence).observationTime();
    }

    @Test
    void recordsExactBoundedInventoryAndImmutableAttributionWithoutPayloadExports() throws Exception {
        UUID run=retire();
        var result=plans.create(command(run),"operator");
        var plan=result.plan();
        assertThat(result.replayed()).isFalse();
        assertThat(plan.artifacts()).hasSize(8);
        assertThat(plan.artifacts()).allSatisfy(a -> assertThat(a.sha256()).matches("[0-9a-f]{64}"));
        assertThat(plan.artifacts()).extracting(ProjectionPurgePlan.Artifact::kind)
                .contains("RUN","GENERATION","MANIFEST_ITEM","RECEIPT","PROJECTION","ROUTING_DECISION");
        assertThat(plan.deletionPermitted()).isFalse();
        assertThat(plan.actor()).isEqualTo("operator");
        assertThat(plans.get(plan.requestId())).isEqualTo(plan);
        assertThat(mapper.writeValueAsString(plan)).doesNotContain("PRIVATE-PLAN","original_payload","canonical_payload");
        assertThat(plans.review(plan.requestId()).assessment()).isEqualTo("CURRENT_REVIEW_ONLY");
    }

    @Test
    void currentReviewAndCreationOnlyWriteTheNewPlanTable() {
        UUID run=retire();
        var before=image();
        var plan=plans.create(command(run),"operator").plan();
        plans.review(plan.requestId());
        assertThat(image()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class)).isEqualTo(1);
    }

    @Test
    void exactReplayReturnsOriginalEvenAfterRoutingChangesButConflictingIntentFails() {
        UUID run=retire();
        var command=command(run);
        var original=plans.create(command,"operator").plan();
        UUID next=build();
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),next,0,3,"later route"),"operator");
        var replay=plans.create(command,"operator");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.plan()).isEqualTo(original);
        assertThat(plans.review(command.requestId()).blockers()).containsExactly("ROUTE_REVISION_CHANGED");
        assertThatThrownBy(() -> plans.create(command,"another-operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(command.requestId(),run,3,"different reason"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class)).isEqualTo(1);
    }

    @Test
    void isolatedMaintainedAndYoungRetiredTargetsCannotBePlanned() {
        UUID run=build();
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,0,"isolated"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),run,0,0,"promote"),"operator");
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,1,"active"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator");
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,2,"warm shadow"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),run,2,"retire"),"operator");
        doCallRealMethod().when(evidence).observationTime();
        assertThatThrownBy(() -> plans.create(command(run),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class)).isZero();
    }

    @Test
    void staleRevisionAndOtherScopesCannotCreateOrReadPlans() {
        UUID run=retire();
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,2,"stale"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        var plan=plans.create(command(run),"operator").plan();
        var other=new ProjectionPurgePlanService(repository,evidence,policy,routes,new ProjectionOperationsProperties(30,100000),kafka,"other");
        assertThatThrownBy(() -> other.get(plan.requestId())).isInstanceOf(ProjectionPurgePlanNotFoundException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> other.create(command(run),"operator")))
                .isInstanceOf(ProjectionPurgePlanNotFoundException.class);
        assertThatThrownBy(() -> plans.get(UUID.randomUUID())).isInstanceOf(ProjectionPurgePlanNotFoundException.class);
    }

    @Test
    void changedPolicyMarksAnExistingPlanStale() {
        var plan=plans.create(command(retire()),"operator").plan();
        var changed=new ProjectionPurgePlanService(repository,evidence,policy,routes,new ProjectionOperationsProperties(45,100000),kafka,"primary");
        assertThat(new TransactionTemplate(transactions).execute(status -> changed.review(plan.requestId())).blockers())
                .contains("REVIEW_POLICY_CHANGED");
    }

    @Test
    void sameCountContentDriftIsDetectedByRowFingerprints() {
        UUID run=retire();
        var plan=plans.create(command(run),"operator").plan();
        jdbc.update("UPDATE payment_event_projections SET merchant_reference='changed-by-external-writer' WHERE consumer_name=?","shadow-"+run);
        var review=plans.review(plan.requestId());
        assertThat(review.blockers()).containsExactly("ARTIFACTS_CHANGED");
        assertThat(review.assessment()).isEqualTo("STALE_REPLAN_REQUIRED");
        assertThat(review.deletionPermitted()).isFalse();
    }

    @Test
    void readOnlyReviewKeepsOneSnapshotWhileExternalDriftCommits() throws Exception {
        UUID run=retire();
        var plan=plans.create(command(run),"operator").plan();
        var observed=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        doAnswer(call -> {
            Instant time=Instant.now().plus(31,ChronoUnit.DAYS);
            // Establish the transaction snapshot before allowing the concurrent writer.
            jdbc.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            observed.countDown();
            if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("snapshot release timed out");
            return time;
        }).when(evidence).observationTime();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> plans.review(plan.requestId()));
            try {
                assertThat(observed.await(10,TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE payment_event_projections SET merchant_reference='concurrent-change' WHERE consumer_name=?","shadow-"+run);
            } finally { release.countDown(); }
            assertThat(worker.get(10,TimeUnit.SECONDS).assessment()).isEqualTo("CURRENT_REVIEW_ONLY");
        } finally { doReturn(Instant.now().plus(31,ChronoUnit.DAYS)).when(evidence).observationTime(); }
        assertThat(plans.review(plan.requestId()).blockers()).containsExactly("ARTIFACTS_CHANGED");
    }

    @Test
    void v12UpgradePreservesExistingEvidenceAndAddsEmptyPlanningStorage() throws Exception {
        String schema="purge_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("purge_upgrade_[0-9a-f]{32}");
        try (var connection=dataSource.getConnection()) {
            String original=connection.getSchema();
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var isolated=new JdbcTemplate(ds);
            isolated.execute("CREATE SCHEMA \""+schema+"\"");
            try {
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("12").load().migrate();
                connection.setSchema(schema);
                isolated.update("INSERT INTO payments(id,merchant_reference,amount,currency,status,version,created_at,updated_at) VALUES (?,'upgrade-evidence',10,'INR','CREATED',0,now(),now())",UUID.randomUUID());
                String before=isolated.queryForObject("SELECT jsonb_agg(to_jsonb(t))::text FROM payments t",String.class);
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                // V16 adds explicit legacy ownership; every pre-existing financial field stays exact.
                assertThat(isolated.queryForObject("SELECT jsonb_agg(to_jsonb(t)-'merchant_id')::text FROM payments t",String.class)).isEqualTo(before);
                assertThat(isolated.queryForObject("SELECT merchant_id FROM payments",UUID.class))
                        .isEqualTo(com.fincore.payments.domain.MerchantIdentity.LEGACY_ID);
                assertThat(isolated.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class)).isZero();
            } finally {
                connection.setSchema(original);
                isolated.execute("DROP SCHEMA \""+schema+"\" CASCADE");
            }
        }
    }

    @Test
    void oversizedInventoryFailsAtomicallyAndExistingReviewReportsBudgetDrift() {
        UUID run=retire();
        var plan=plans.create(command(run),"operator").plan();
        addReceipts(run,993); // Original 8 + 993 = 1001; includes one sentinel beyond the budget.
        assertThatThrownBy(() -> plans.create(command(run),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class)
                .hasMessageContaining("1000-artifact");
        assertThat(plans.review(plan.requestId()).blockers()).containsExactly("ARTIFACT_BUDGET_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class)).isEqualTo(1);
    }

    @Test
    void exactBudgetIsAcceptedAndIncludesEveryRow() {
        UUID run=retire();
        addReceipts(run,992);
        var plan=plans.create(command(run),"operator").plan();
        assertThat(plan.artifacts()).hasSize(1000);
        assertThat(plans.review(plan.requestId()).blockers()).isEmpty();
    }

    @Test
    void databaseRejectsPlanMutationAndMismatchedRetirementEvidence() {
        UUID run=retire();
        var plan=plans.create(command(run),"operator").plan();
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_purge_plans SET reason='rewrite' WHERE request_id=?",plan.requestId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projection_purge_plans WHERE request_id=?",plan.requestId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var forged=new ProjectionPurgePlan(UUID.randomUUID(),1,run,plan.sourceName(),plan.topic(),0,3,1,plan.retiredAt(),
                plan.observedAt(),30,"operator","false retirement",plan.assessment(),false,plan.preservationRequirements(),plan.artifacts());
        assertThatThrownBy(() -> repository.insert(forged)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void routingFenceContentionRefusesWithoutPartialAuditAndRetrySucceeds() throws Exception {
        UUID run=retire();
        var command=command(run);
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                routes.fence(); locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("lock release timed out"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> plans.create(command,"operator")).isInstanceOf(ProjectionRoutingConflictException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_purge_plans",Long.class)).isZero();
            } finally { release.countDown(); }
            worker.get(10,TimeUnit.SECONDS);
        }
        assertThat(plans.create(command,"operator").replayed()).isFalse();
    }

    @Test
    void replacedTargetsUseTheDepartingDecisionRatherThanTheIncomingTarget() {
        UUID first=build();
        UUID next=build();
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),first,0,0,"promote"),"operator");
        routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator");
        routing.replace(new ProjectionReplacementCommand(UUID.randomUUID(),first,next,0,2,"replace"),"operator");
        var plan=plans.create(command(first),"operator").plan();
        assertThat(plan.retirementRevision()).isEqualTo(3);
        assertThat(plan.artifacts()).extracting(ProjectionPurgePlan.Artifact::kind).contains("DEPARTING_DECISION");
        assertThat(plans.review(plan.requestId()).blockers()).isEmpty();
    }

    @Test
    void serviceRejectsUnboundedAndMultilineAttribution() {
        UUID run=retire();
        assertThatThrownBy(() -> plans.create(command(run),"operator\nforged")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"x".repeat(501)),"operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"a\u2028b"),"operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plans.create(null,"operator")).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID build() { UUID run=UUID.randomUUID(); rebuild.create(run,"builder","plan fixture"); rebuild.step(run,100); return run; }
    private UUID retire() {
        UUID run=build();
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),run,0,0,"promote"),"operator");
        routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator");
        routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),run,2,"retire"),"operator");
        return run;
    }
    private ProjectionPurgePlanCommand command(UUID run) { return new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"Review retained target"); }
    private java.util.Map<String,String> image() {
        var image=new java.util.LinkedHashMap<String,String>();
        for (String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename NOT IN ('flyway_schema_history','projection_purge_plans')",String.class))
            image.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM "+table+" t",String.class));
        return image;
    }
    private void addReceipts(UUID run,int count) {
        jdbc.update("""
                WITH extras AS MATERIALIZED (SELECT gen_random_uuid() AS event_id,gen_random_uuid() AS payment_id FROM generate_series(1,?))
                INSERT INTO consumed_payment_events(consumer_name,event_id,payment_id,payment_version,event_type,schema_version,payload)
                SELECT ?,e.event_id,e.payment_id,c.payment_version,c.event_type,c.schema_version,
                    jsonb_set(jsonb_set(jsonb_set(c.payload::jsonb,'{eventId}',to_jsonb(e.event_id::text)),
                        '{paymentId}',to_jsonb(e.payment_id::text)),'{data,id}',to_jsonb(e.payment_id::text))::text
                FROM extras e CROSS JOIN LATERAL (SELECT * FROM consumed_payment_events WHERE consumer_name='payment-projection-v1' LIMIT 1) c
                """,count,"shadow-"+run);
    }
}
