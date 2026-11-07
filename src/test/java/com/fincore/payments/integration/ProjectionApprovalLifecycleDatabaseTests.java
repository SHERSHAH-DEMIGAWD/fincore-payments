package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.*;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.ProjectionApprovalLifecycle.RevocationCommand;
import com.fincore.payments.dto.ProjectionPreservation.*;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false",
        "fincore.projection-approvals.validity-minutes=60"})
abstract class ProjectionApprovalLifecycleDatabaseTests {
    @Autowired ProjectionApprovalLifecycleService lifecycle;
    @MockitoSpyBean ProjectionApprovalLifecycleRepository revocations;
    @Autowired ProjectionPreservationService preservation;
    @MockitoSpyBean ProjectionPreservationRepository storage;
    @Autowired ProjectionPreservationVerifier verifier;
    @Autowired ProjectionPurgePlanService plans;
    @Autowired ProjectionPurgePlanRepository inventory;
    @MockitoSpyBean ProjectionEvidenceRepository evidence;
    @Autowired ProjectionRoutingRepository routes;
    @Autowired ProjectionRoutingService routing;
    @Autowired ProjectionRebuildService rebuild;
    @Autowired PaymentService payments;
    @Autowired PaymentEventConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired KafkaEventProperties kafka;
    @Autowired ProjectionOperationsProperties operatingProperties;
    UUID run,planId;
    Instant observed;
    ProjectionPreservation preserved;
    ApprovalCommand approvalIntent;
    Approval approval;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,
                    projection_preservations,projection_purge_plans,projection_routes,projection_route_audit,
                    projection_rebuild_items,projection_rebuild_generations,projection_rebuild_runs,
                    consumer_recovery_audit,consumer_event_failures,consumer_event_positions,outbox_requeue_audit,
                    payment_event_projections,consumed_payment_events,local_payment_event_inbox,payment_outbox,
                    ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments
                """);
        UUID payment=payments.createPayment("approval-lifecycle-"+UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-LIFECYCLE",BigDecimal.TEN,"INR")).payment().id();
        consumer.consume(mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id=?",
                String.class,payment),PaymentEvent.class));
        run=UUID.randomUUID(); rebuild.create(run,"builder","approval lifecycle fixture"); rebuild.step(run,100);
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),run,0,0,"promote"),"operator");
        routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator");
        routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),run,2,"retire"),"operator");
        // Advance the observation clock without rewriting immutable retirement history.
        observed=Instant.now().plus(31,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        doReturn(observed).when(evidence).observationTime();
        planId=plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"lifecycle plan"),"operator").plan().requestId();
        preserved=preservation.preserve(planId,new Command(UUID.randomUUID(),3,"preserve lifecycle evidence"),"operator").preservation();
        approvalIntent=approveIntent();
        approval=preservation.approve(planId,approvalIntent,"operator").approval();
    }

    @Test
    void newApprovalSnapshotsItsConfiguredDurationAndObservationTime() {
        assertThat(approval.validityMinutes()).isEqualTo(60);
        assertThat(approval.approvedAt()).isEqualTo(observed);
        assertThat(approval.expiresAt()).isEqualTo(observed.plus(60,ChronoUnit.MINUTES));
        assertThat(preservation.reviewApproval(approval.requestId()).assessment()).isEqualTo("CURRENT_APPROVAL_REVIEW_ONLY");
        assertThat(approval.deletionPermitted()).isFalse();
    }

    @Test
    void expiryBoundaryIsInclusiveAndDoesNotRewriteTheAcceptedAudit() {
        doReturn(approval.expiresAt().minus(1,ChronoUnit.MICROS)).when(evidence).observationTime();
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).isEmpty();
        doReturn(approval.expiresAt()).when(evidence).observationTime();
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).containsExactly("APPROVAL_EXPIRED");
        doReturn(approval.expiresAt().plusSeconds(1)).when(evidence).observationTime();
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).containsExactly("APPROVAL_EXPIRED");
        assertThat(preservation.approval(approval.requestId())).isEqualTo(approval);
        var renewed=preservation.approve(planId,approveIntent(),"operator").approval();
        assertThat(renewed.approvedAt()).isEqualTo(approval.expiresAt().plusSeconds(1));
        assertThat(preservation.reviewApproval(renewed.requestId()).blockers()).isEmpty();
    }

    @Test
    void anObservationBeforeApprovalIsUncertainInsteadOfExtendingAuthority() {
        doReturn(approval.approvedAt().minus(1,ChronoUnit.MICROS)).when(evidence).observationTime();
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).containsExactly("APPROVAL_TIME_UNCERTAIN");
    }

    @Test
    void changedConfigurationAffectsOnlyNewApprovals() {
        var changed=new ProjectionPreservationService(storage,inventory,plans,evidence,routes,verifier,
                new ProjectionApprovalProperties(5));
        var next=new TransactionTemplate(transactions).execute(status -> changed.approve(planId,approveIntent(),"operator")).approval();
        assertThat(next.validityMinutes()).isEqualTo(5);
        assertThat(next.expiresAt()).isEqualTo(next.approvedAt().plus(5,ChronoUnit.MINUTES));
        assertThat(preservation.approval(approval.requestId())).isEqualTo(approval);
        var replay=new TransactionTemplate(transactions).execute(status -> changed.approve(planId,approvalIntent,"operator"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.approval()).isEqualTo(approval);
        doReturn(observed.plus(6,ChronoUnit.MINUTES)).when(evidence).observationTime();
        assertThat(new TransactionTemplate(transactions).execute(status -> changed.reviewApproval(approval.requestId())).blockers()).isEmpty();
        assertThat(preservation.reviewApproval(next.requestId()).blockers()).containsExactly("APPROVAL_EXPIRED");
    }

    @Test
    void exactApprovalReplaySurvivesExpiryRevocationAndLaterRouting() {
        var revocationIntent=revokeIntent();
        var revoked=lifecycle.revoke(approval.requestId(),revocationIntent,"operator").revocation();
        doReturn(approval.expiresAt()).when(evidence).observationTime();
        changeRoute();
        var replay=preservation.approve(planId,approvalIntent,"operator");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.approval()).isEqualTo(approval);
        assertThat(lifecycle.revoke(approval.requestId(),revocationIntent,"operator").revocation()).isEqualTo(revoked);
        assertThat(preservation.reviewApproval(approval.requestId()).blockers())
                .contains("APPROVAL_EXPIRED","APPROVAL_REVOKED","ROUTE_REVISION_CHANGED");
    }

    @Test
    void revocationRecordsPrincipalIntentAndExactRetryReturnsOriginal() {
        var intent=revokeIntent();
        var first=lifecycle.revoke(approval.requestId(),intent,"reviewer");
        assertThat(first.replayed()).isFalse();
        assertThat(first.revocation().actor()).isEqualTo("reviewer");
        assertThat(first.revocation().reason()).isEqualTo(intent.reason());
        assertThat(first.revocation().deletionPermitted()).isFalse();
        assertThat(lifecycle.revocation(approval.requestId())).isEqualTo(first.revocation());
        assertThat(lifecycle.revoke(approval.requestId(),intent,"reviewer").revocation()).isEqualTo(first.revocation());
        assertThat(lifecycle.revoke(approval.requestId(),intent,"reviewer").replayed()).isTrue();
        assertThat(count("projection_approval_revocations")).isEqualTo(1);
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).containsExactly("APPROVAL_REVOKED");
    }

    @Test
    void revokedApprovalCannotGainAnotherRevocationOrReuseIntentWithDifferentAttribution() {
        var intent=revokeIntent();
        lifecycle.revoke(approval.requestId(),intent,"operator");
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),revokeIntent(),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),intent,"another-operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),new RevocationCommand(intent.requestId(),"changed reason"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(count("projection_approval_revocations")).isEqualTo(1);
    }

    @Test
    void aRevocationRequestUuidCannotBeReusedForAnotherApproval() {
        var another=preservation.approve(planId,approveIntent(),"operator").approval();
        var intent=revokeIntent();
        lifecycle.revoke(approval.requestId(),intent,"operator");
        assertThatThrownBy(() -> lifecycle.revoke(another.requestId(),intent,"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(revocations.revocation(another.requestId())).isEmpty();
    }

    @Test
    void staleExpiredApprovalCanBeRevokedWithoutReadingCorruptPreservationBodies() {
        changeRoute();
        doReturn(approval.expiresAt().plusSeconds(1)).when(evidence).observationTime();
        doThrow(new AssertionError("Revocation must not load preservation bodies")).when(storage).find(preserved.requestId());
        assertThat(lifecycle.revoke(approval.requestId(),revokeIntent(),"operator").replayed()).isFalse();
        assertThat(count("projection_approval_revocations")).isEqualTo(1);
    }

    @Test
    void dedicatedFenceContentionDoesNotInsertAndTheSameRequestCanRetry() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var intent=revokeIntent();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                revocations.fence(); locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),intent,"operator"))
                        .isInstanceOf(ProjectionPurgePlanConflictException.class);
                assertThat(count("projection_approval_revocations")).isZero();
            } finally { release.countDown(); }
            worker.get(10,TimeUnit.SECONDS);
        }
        assertThat(lifecycle.revoke(approval.requestId(),intent,"operator").replayed()).isFalse();
    }

    @Test
    void heldRoutingFenceDoesNotBlockIndependentRevocation() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                routes.fence(); locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(lifecycle.revoke(approval.requestId(),revokeIntent(),"operator").replayed()).isFalse();
            } finally { release.countDown(); }
            worker.get(10,TimeUnit.SECONDS);
        }
    }

    @Test
    void lateRevocationWriteFailureRollsBackItsAuditAtomically() {
        doAnswer(call -> { call.callRealMethod(); throw new org.springframework.dao.DataIntegrityViolationException("simulated late revocation failure"); })
                .when(revocations).revoke(any(),any(),anyString(),anyString());
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),revokeIntent(),"operator"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("projection_approval_revocations")).isZero();
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).isEmpty();
    }

    @Test
    void concurrentDifferentRequestsProduceExactlyOneRevocation() throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> revoke=() -> {
                ready.countDown(); await(start);
                try { lifecycle.revoke(approval.requestId(),revokeIntent(),"operator"); return true; }
                catch (ProjectionPurgePlanConflictException expected) { return false; }
            };
            var first=executor.submit(revoke); var second=executor.submit(revoke);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("projection_approval_revocations")).isEqualTo(1);
    }

    @Test
    void configuredSourceIsolationCoversBothRevocationReadsAndWrites() {
        var otherPolicy=new ProjectionEvidenceService(evidence,routes,operatingProperties,kafka,"other");
        var otherPlans=new ProjectionPurgePlanService(inventory,evidence,otherPolicy,routes,operatingProperties,kafka,"other");
        var other=new ProjectionApprovalLifecycleService(revocations,storage,otherPlans);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                other.revoke(approval.requestId(),revokeIntent(),"operator"))).isInstanceOf(ProjectionPurgePlanNotFoundException.class);
        lifecycle.revoke(approval.requestId(),revokeIntent(),"operator");
        assertThatThrownBy(() -> other.revocation(approval.requestId())).isInstanceOf(ProjectionPurgePlanNotFoundException.class);
    }

    @Test
    void aReadOnlyReviewRetainsItsSnapshotWhileRevocationCommits() throws Exception {
        var snapshot=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> {
            jdbc.queryForObject("SELECT count(*) FROM projection_approval_revocations",Long.class);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            snapshot.countDown(); await(release); return observed;
        }).when(evidence).observationTime();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> preservation.reviewApproval(approval.requestId()));
            try {
                assertThat(snapshot.await(10,TimeUnit.SECONDS)).isTrue();
                lifecycle.revoke(approval.requestId(),revokeIntent(),"operator");
            } finally { release.countDown(); }
            assertThat(worker.get(10,TimeUnit.SECONDS).blockers()).isEmpty();
        } finally { doReturn(observed).when(evidence).observationTime(); }
        assertThat(preservation.reviewApproval(approval.requestId()).blockers()).containsExactly("APPROVAL_REVOKED");
    }

    @Test
    void databaseRejectsMissingPartialOrInvalidNewApprovalValidity() {
        assertInvalidValidity(null,null);
        assertInvalidValidity(60,null);
        assertInvalidValidity(null,approval.expiresAt());
        assertInvalidValidity(0,approval.expiresAt());
        assertInvalidValidity(1441,approval.approvedAt().plus(1441,ChronoUnit.MINUTES));
        assertInvalidValidity(60,approval.expiresAt().plusSeconds(1));
        assertThat(count("projection_preservation_approvals")).isEqualTo(1);
    }

    @Test
    void databaseProtectsRevocationIdentityAndOneRecordPerApproval() {
        var record=lifecycle.revoke(approval.requestId(),revokeIntent(),"operator").revocation();
        assertThatThrownBy(() -> jdbc.update("UPDATE projection_approval_revocations SET reason='rewrite' WHERE request_id=?",record.requestId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projection_approval_revocations WHERE request_id=?",record.requestId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO projection_approval_revocations(request_id,approval_id,actor,reason) VALUES (?,?,?,?)",
                UUID.randomUUID(),approval.requestId(),"operator","duplicate"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO projection_approval_revocations(request_id,approval_id,actor,reason) VALUES (?,?,?,?)",
                UUID.randomUUID(),UUID.randomUUID(),"operator","missing approval"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void revocationOnlyWritesItsOwnAuditAndInspectionIsReadOnly() {
        var before=image();
        lifecycle.revoke(approval.requestId(),revokeIntent(),"operator");
        lifecycle.revocation(approval.requestId()); preservation.reviewApproval(approval.requestId());
        assertThat(image()).isEqualTo(before);
        assertThat(count("projection_approval_revocations")).isEqualTo(1);
    }

    @Test
    void missingApprovalAndInvalidAttributionDoNotCreateAuditEvidence() {
        assertThatThrownBy(() -> lifecycle.revoke(UUID.randomUUID(),revokeIntent(),"operator"))
                .isInstanceOf(ProjectionPurgePlanNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.revocation(approval.requestId())).isInstanceOf(ProjectionPurgePlanNotFoundException.class);
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),revokeIntent(),"forged\noperator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),new RevocationCommand(UUID.randomUUID(),"x".repeat(501)),"operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.revoke(approval.requestId(),new RevocationCommand(UUID.randomUUID(),"a\u2028b"),"operator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("projection_approval_revocations")).isZero();
    }

    @Test
    void v14UpgradePreservesLegacyAuditReplayAndRequiresExplicitNewValidity() throws Exception {
        String schema="approval_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("approval_upgrade_[0-9a-f]{32}");
        try (var connection=dataSource.getConnection()) {
            String original=connection.getSchema();
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var isolated=new JdbcTemplate(ds);
            var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            isolated.execute("CREATE SCHEMA \""+schema+"\"");
            try {
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("14").load().migrate();
                connection.setSchema(schema);
                var buildRepo=new ProjectionRebuildRepository(isolated);
                var routeRepo=new ProjectionRoutingRepository(isolated);
                var routeService=new ProjectionRoutingService(routeRepo,buildRepo,new ProjectionReconciliationRepository(isolated),kafka,"primary",operatingProperties);
                UUID target=UUID.randomUUID();
                tx.executeWithoutResult(status -> {
                    buildRepo.insertRun(target,"shadow-"+target,"primary",kafka.topic(),"builder","v14 fixture");
                    buildRepo.beginGeneration(target,0,target,null,"builder","v14 fixture",new ReconciliationGapContext(0,0,0,0));
                    buildRepo.finishPlan(target,0);
                });
                tx.executeWithoutResult(status -> routeService.promote(new ProjectionPromotionCommand(UUID.randomUUID(),target,0,0,"promote"),"operator"));
                tx.executeWithoutResult(status -> routeService.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator"));
                tx.executeWithoutResult(status -> routeService.retire(new ProjectionRetirementCommand(UUID.randomUUID(),target,2,"retire"),"operator"));
                var inventoryRepo=new ProjectionPurgePlanRepository(isolated,mapper);
                var evidenceRepo=spy(new ProjectionEvidenceRepository(isolated));
                doReturn(observed).when(evidenceRepo).observationTime();
                var policy=new ProjectionEvidenceService(evidenceRepo,routeRepo,operatingProperties,kafka,"primary");
                var planService=new ProjectionPurgePlanService(inventoryRepo,evidenceRepo,policy,routeRepo,operatingProperties,kafka,"primary");
                var plan=tx.execute(status -> planService.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),target,3,"v14 plan"),"operator")).plan();
                var copyRepo=new ProjectionPreservationRepository(isolated);
                var currentService=new ProjectionPreservationService(copyRepo,inventoryRepo,planService,evidenceRepo,routeRepo,verifier,
                        new ProjectionApprovalProperties(60));
                var copy=tx.execute(status -> currentService.preserve(plan.requestId(),new Command(UUID.randomUUID(),3,"v14 copy"),"operator")).preservation();
                var legacyIntent=new ApprovalCommand(UUID.randomUUID(),copy.requestId(),3,copy.sha256(),true,"v14 accepted approval");
                // Latest approve() requires V15 columns. The old-format row must be inserted with V14 SQL.
                isolated.update("""
                        INSERT INTO projection_preservation_approvals(request_id,plan_id,preservation_id,expected_revision,
                            export_sha256,preservation_acknowledged,actor,reason) VALUES (?,?,?,3,?,true,'operator',?)
                        """,legacyIntent.requestId(),plan.requestId(),copy.requestId(),copy.sha256(),legacyIntent.reason());
                String before=isolated.queryForObject("SELECT to_jsonb(a)::text FROM projection_preservation_approvals a",String.class);
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                assertThat(isolated.queryForObject("SELECT (to_jsonb(a)-'validity_minutes'-'expires_at')::text FROM projection_preservation_approvals a",String.class))
                        .isEqualTo(before);
                var legacy=copyRepo.approval(legacyIntent.requestId()).orElseThrow();
                assertThat(legacy.validityMinutes()).isNull(); assertThat(legacy.expiresAt()).isNull();
                assertThat(tx.execute(status -> currentService.reviewApproval(legacy.requestId())).blockers())
                        .contains("APPROVAL_VALIDITY_UNSPECIFIED");
                var replay=tx.execute(status -> currentService.approve(plan.requestId(),legacyIntent,"operator"));
                assertThat(replay.replayed()).isTrue(); assertThat(replay.approval()).isEqualTo(legacy);
                var revokeRepo=new ProjectionApprovalLifecycleRepository(isolated);
                var legacyLifecycle=new ProjectionApprovalLifecycleService(revokeRepo,copyRepo,planService);
                tx.executeWithoutResult(status -> legacyLifecycle.revoke(legacy.requestId(),revokeIntent(),"operator"));
                var nextIntent=new ApprovalCommand(UUID.randomUUID(),copy.requestId(),3,copy.sha256(),true,"new bounded approval");
                var next=tx.execute(status -> currentService.approve(plan.requestId(),nextIntent,"operator")).approval();
                assertThat(next.validityMinutes()).isEqualTo(60); assertThat(next.expiresAt()).isEqualTo(observed.plus(60,ChronoUnit.MINUTES));
                assertThat(tx.execute(status -> currentService.reviewApproval(next.requestId())).blockers()).isEmpty();
                assertThat(tx.execute(status -> currentService.approve(plan.requestId(),legacyIntent,"operator")).approval()).isEqualTo(legacy);
            } finally {
                connection.setSchema(original);
                isolated.execute("DROP SCHEMA \""+schema+"\" CASCADE");
            }
        }
    }

    private void assertInvalidValidity(Integer minutes,Instant expiresAt) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO projection_preservation_approvals(request_id,plan_id,preservation_id,expected_revision,
                    export_sha256,preservation_acknowledged,approved_at,actor,reason,validity_minutes,expires_at)
                VALUES (?,?,?,3,?,true,?,'operator','invalid validity',?,?)
                """,UUID.randomUUID(),planId,preserved.requestId(),preserved.sha256(),java.sql.Timestamp.from(approval.approvedAt()),
                minutes,expiresAt==null ? null : java.sql.Timestamp.from(expiresAt)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private ApprovalCommand approveIntent() {
        return new ApprovalCommand(UUID.randomUUID(),preserved.requestId(),3,preserved.sha256(),true,"review lifecycle evidence");
    }
    private RevocationCommand revokeIntent() { return new RevocationCommand(UUID.randomUUID(),"withdraw preservation approval"); }
    private void changeRoute() {
        UUID next=UUID.randomUUID(); rebuild.create(next,"builder","later target"); rebuild.step(next,100);
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),next,0,3,"later route"),"operator");
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private java.util.Map<String,String> image() {
        var image=new java.util.LinkedHashMap<String,String>();
        for (String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename NOT IN ('flyway_schema_history','projection_approval_revocations')",String.class))
            image.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM "+table+" t",String.class));
        return image;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }
}
