package com.fincore.payments.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.*;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.*;
import com.fincore.payments.dto.ProjectionPreservation.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.repository.*;
import com.fincore.payments.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fincore.payments.service.ProjectionPreservationVerifier.*;

@SpringBootTest(properties={"fincore.events.transport=local","fincore.outbox.enabled=false"})
abstract class ProjectionPreservationDatabaseTests {
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
    @Autowired ProjectionOperationsProperties properties;
    UUID run,planId;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE TABLE payment_amount_operations,payment_amount_bindings,merchant_webhook_retries,merchant_webhook_attempts,merchant_webhook_deliveries,merchant_webhook_subscription_changes,merchant_webhook_subscriptions,provider_webhook_receipts,payment_provider_operations,payment_provider_bindings,projection_approval_revocations,projection_preservation_approvals,projection_preservations,projection_purge_plans,
                    projection_routes,projection_route_audit,projection_rebuild_items,projection_rebuild_generations,
                    projection_rebuild_runs,consumer_recovery_audit,consumer_event_failures,consumer_event_positions,
                    outbox_requeue_audit,payment_event_projections,consumed_payment_events,local_payment_event_inbox,
                    payment_outbox,ledger_entries,ledger_transactions,ledger_accounts,idempotency_records,payments
                """);
        UUID payment=payments.createPayment("preserve-"+UUID.randomUUID(),
                new CreatePaymentRequest("PRIVATE-\u20b9-\ud83d\ude00",BigDecimal.TEN,"INR")).payment().id();
        consumer.consume(mapper.readValue(jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE payment_id=?",String.class,payment),PaymentEvent.class));
        run=UUID.randomUUID(); rebuild.create(run,"builder","preservation fixture"); rebuild.step(run,100);
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),run,0,0,"promote"),"operator");
        routing.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator");
        routing.retire(new ProjectionRetirementCommand(UUID.randomUUID(),run,2,"retire"),"operator");
        doReturn(Instant.now().plus(31,ChronoUnit.DAYS)).when(evidence).observationTime();
        planId=plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"preservation plan"),"operator").plan().requestId();
    }

    @Test
    void exportContainsAllExactRecordBodiesAndItsWholeFileDigest() throws Exception {
        var result=preservation.preserve(planId,command(),"operator");
        var p=result.preservation();
        var file=preservation.download(p.requestId());
        var bundle=mapper.readValue(file.bytes(),Bundle.class);
        assertThat(result.replayed()).isFalse();
        assertThat(p.artifactCount()).isEqualTo(8);
        assertThat(p.exportBytes()).isEqualTo(file.bytes().length);
        assertThat(file.sha256()).isEqualTo(sha256(file.bytes()));
        assertThat(bundle.plan()).isEqualTo(plans.get(planId));
        assertThat(bundle.records()).hasSize(8).allSatisfy(row -> assertThat(row.sha256()).isEqualTo(sha256(bytes(row.body()))));
        String json=new String(file.bytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("PRIVATE-\u20b9-\ud83d\ude00");
        assertThat(file.bytes().length).isGreaterThan(json.length());
        var receipt=bundle.records().stream().filter(r -> r.kind().equals("RECEIPT")).findFirst().orElseThrow();
        assertThat(mapper.readTree(receipt.body()).get("payload").asText()).isEqualTo(jdbc.queryForObject(
                "SELECT payload FROM consumed_payment_events WHERE consumer_name=?",String.class,"shadow-"+run));
        assertThat(p.deletionPermitted()).isFalse();
        assertThat(preservation.get(p.requestId())).isEqualTo(p);
    }

    @Test
    void approvalBindsTheExactPreservedDigestAndExplicitAcknowledgement() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var result=preservation.approve(planId,approval(p),"reviewer");
        var a=result.approval();
        assertThat(a.exportSha256()).isEqualTo(p.sha256());
        assertThat(a.preservationAcknowledged()).isTrue();
        assertThat(a.actor()).isEqualTo("reviewer");
        assertThat(a.assessment()).isEqualTo("PRESERVATION_REVIEW_APPROVED_NO_DELETION_AUTHORITY");
        assertThat(a.deletionPermitted()).isFalse();
        assertThat(preservation.approval(a.requestId())).isEqualTo(a);
        assertThat(preservation.reviewApproval(a.requestId()).assessment()).isEqualTo("CURRENT_APPROVAL_REVIEW_ONLY");
    }

    @Test
    void bothExactRetriesRecoverHistoricalResultsAfterTheRouteChanges() {
        var command=command();
        var p=preservation.preserve(planId,command,"operator").preservation();
        var approval=approval(p);
        var a=preservation.approve(planId,approval,"operator").approval();
        changeRoute();
        assertThat(preservation.preserve(planId,command,"operator")).isEqualTo(new Result(p,true));
        assertThat(preservation.approve(planId,approval,"operator")).isEqualTo(new ApprovalResult(a,true));
        assertThat(preservation.reviewApproval(a.requestId()).blockers()).containsExactly("ROUTE_REVISION_CHANGED");
        assertThat(preservation.download(p.requestId()).sha256()).isEqualTo(p.sha256());
    }

    @Test
    void reusedIdsCannotChangeOperatorReasonPlanOrDigest() {
        var command=command();
        var p=preservation.preserve(planId,command,"operator").preservation();
        var approval=approval(p);
        preservation.approve(planId,approval,"operator");
        assertThatThrownBy(() -> preservation.preserve(planId,command,"forged"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> preservation.preserve(planId,new Command(command.requestId(),3,"changed"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> preservation.approve(planId,approval,"forged"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> preservation.approve(planId,new ApprovalCommand(approval.requestId(),p.requestId(),3,"0".repeat(64),true,"reviewed"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
    }

    @Test
    void stalePlanOrWrongExpectedRevisionCannotCreatePreservation() {
        assertThatThrownBy(() -> preservation.preserve(planId,new Command(UUID.randomUUID(),2,"stale revision"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        jdbc.update("UPDATE payment_event_projections SET merchant_reference='external drift' WHERE consumer_name=?","shadow-"+run);
        assertThatThrownBy(() -> preservation.preserve(planId,command(),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(count("projection_preservations")).isZero();
    }

    @Test
    void driftAfterPreservationPreventsApprovalButKeepsOriginalDownload() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var original=preservation.download(p.requestId());
        jdbc.update("UPDATE payment_event_projections SET merchant_reference='external drift' WHERE consumer_name=?","shadow-"+run);
        assertThatThrownBy(() -> preservation.approve(planId,approval(p),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(preservation.download(p.requestId()).bytes()).isEqualTo(original.bytes());
        assertThat(count("projection_preservation_approvals")).isZero();
    }

    @Test
    void laterContentDriftStalesAnApprovedReviewWithoutRewritingTheAudit() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var a=preservation.approve(planId,approval(p),"operator").approval();
        jdbc.update("UPDATE payment_event_projections SET merchant_reference='external drift' WHERE consumer_name=?","shadow-"+run);
        assertThat(preservation.reviewApproval(a.requestId()).blockers()).containsExactly("ARTIFACTS_CHANGED");
        assertThat(preservation.approval(a.requestId())).isEqualTo(a);
    }

    @Test
    void corruptedStoredCopyFailsDownloadAndNewApprovalAndStalesExistingApprovalReview() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var a=preservation.approve(planId,approval(p),"operator").approval();
        var real=storage.find(p.requestId()).orElseThrow();
        doReturn(java.util.Optional.of(new ProjectionPreservationRepository.Stored(p,real.json()+" "))).when(storage).find(p.requestId());
        assertThatThrownBy(() -> preservation.download(p.requestId())).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> preservation.approve(planId,approval(p),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(preservation.reviewApproval(a.requestId()).blockers()).containsExactly("PRESERVATION_INTEGRITY_FAILED");
        assertThat(count("projection_preservation_approvals")).isEqualTo(1);
    }

    @Test
    void wrongDigestMissingCopyOtherPlanAndMissingAcknowledgementAreRefused() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        assertThatThrownBy(() -> preservation.approve(planId,new ApprovalCommand(UUID.randomUUID(),p.requestId(),3,"0".repeat(64),true,"reviewed"),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThatThrownBy(() -> preservation.approve(planId,new ApprovalCommand(UUID.randomUUID(),UUID.randomUUID(),3,p.sha256(),true,"reviewed"),"operator"))
                .isInstanceOf(ProjectionPurgePlanNotFoundException.class);
        assertThatThrownBy(() -> preservation.approve(planId,new ApprovalCommand(UUID.randomUUID(),p.requestId(),3,p.sha256(),false,"reviewed"),"operator"))
                .isInstanceOf(IllegalArgumentException.class);
        UUID other=plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"another plan"),"operator").plan().requestId();
        assertThatThrownBy(() -> preservation.approve(other,approval(p),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
        assertThat(count("projection_preservation_approvals")).isZero();
    }

    @Test
    void anotherConfiguredSourceCannotReadExportsOrApprovals() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var a=preservation.approve(planId,approval(p),"operator").approval();
        var otherPolicy=new ProjectionEvidenceService(evidence,routes,properties,kafka,"other");
        var otherPlans=new ProjectionPurgePlanService(inventory,evidence,otherPolicy,routes,properties,kafka,"other");
        var other=new ProjectionPreservationService(storage,inventory,otherPlans,evidence,routes,verifier,new ProjectionApprovalProperties(60));
        for (Runnable read:java.util.List.<Runnable>of(() -> other.get(p.requestId()),() -> other.download(p.requestId()),
                () -> other.approval(a.requestId()),() -> other.reviewApproval(a.requestId())))
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> read.run()))
                    .isInstanceOf(ProjectionPurgePlanNotFoundException.class);
    }

    @Test
    void preservationAndApprovalOnlyAddTheirOwnEvidence() {
        var before=image();
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var a=preservation.approve(planId,approval(p),"operator").approval();
        preservation.download(p.requestId()); preservation.reviewApproval(a.requestId());
        assertThat(image()).isEqualTo(before);
        assertThat(count("projection_preservations")).isEqualTo(1);
        assertThat(count("projection_preservation_approvals")).isEqualTo(1);
    }

    @Test
    void rawByteBudgetRejectsBeforeAnyCopyIsStored() {
        addLargeReceipt(MAX_EXPORT_BYTES,"x");
        replan();
        assertThatThrownBy(() -> preservation.preserve(planId,command(),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class).hasMessageContaining("raw-byte budget");
        assertThat(count("projection_preservations")).isZero();
    }

    @Test
    void escapedSerializedBundleHasItsOwnByteBudget() {
        addLargeReceipt(1_100_000,"\\");
        replan();
        assertThatThrownBy(() -> preservation.preserve(planId,command(),"operator"))
                .isInstanceOf(ProjectionPurgePlanConflictException.class).hasMessageContaining("Serialized preservation");
        assertThat(count("projection_preservations")).isZero();
    }

    @Test
    void lateStorageFailureRollsBackTheCopy() {
        doAnswer(call -> { call.callRealMethod(); throw new org.springframework.dao.DataIntegrityViolationException("simulated late storage failure"); })
                .when(storage).insert(any(),anyString());
        assertThatThrownBy(() -> preservation.preserve(planId,command(),"operator"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("projection_preservations")).isZero();
    }

    @Test
    void databaseGuardsProtectCopiesAndApprovalsAndRejectInvalidDigestOrBinding() {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var a=preservation.approve(planId,approval(p),"operator").approval();
        for (String table:java.util.List.of("projection_preservations","projection_preservation_approvals")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE "+table+" SET reason='rewrite'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM "+table)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        String json=storage.find(p.requestId()).orElseThrow().json();
        var forged=new ProjectionPreservation(UUID.randomUUID(),planId,1,3,p.preservedAt(),"operator","forged",p.artifactCount(),p.exportBytes(),"0".repeat(64),p.assessment(),false);
        assertThatThrownBy(() -> storage.insert(forged,json)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(preservation.approval(a.requestId())).isEqualTo(a);
    }

    @Test
    void routingFenceContentionLeavesNoCopyAndRetryWorks() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var command=command();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                routes.fence(); locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> preservation.preserve(planId,command,"operator")).isInstanceOf(ProjectionRoutingConflictException.class);
                assertThat(count("projection_preservations")).isZero();
            } finally { release.countDown(); }
            worker.get(10,TimeUnit.SECONDS);
        }
        assertThat(preservation.preserve(planId,command,"operator").replayed()).isFalse();
    }

    @Test
    void approvalReviewUsesOneReadOnlySnapshotWhileDriftCommits() throws Exception {
        var p=preservation.preserve(planId,command(),"operator").preservation();
        var a=preservation.approve(planId,approval(p),"operator").approval();
        var observed=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> {
            jdbc.queryForObject("SELECT count(*) FROM projection_preservation_approvals",Long.class);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            observed.countDown(); await(release); return Instant.now().plus(31,ChronoUnit.DAYS);
        }).when(evidence).observationTime();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> preservation.reviewApproval(a.requestId()));
            try {
                assertThat(observed.await(10,TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE payment_event_projections SET merchant_reference='concurrent drift' WHERE consumer_name=?","shadow-"+run);
            } finally { release.countDown(); }
            assertThat(worker.get(10,TimeUnit.SECONDS).blockers()).isEmpty();
        } finally { doReturn(Instant.now().plus(31,ChronoUnit.DAYS)).when(evidence).observationTime(); }
        assertThat(preservation.reviewApproval(a.requestId()).blockers()).containsExactly("ARTIFACTS_CHANGED");
    }

    @Test
    void preserveSuspendsAnOlderCallerSnapshotAndSeesCommittedDrift() throws Exception {
        var outer=new TransactionTemplate(transactions); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor=Executors.newSingleThreadExecutor()) {
            outer.executeWithoutResult(status -> {
                String original=jdbc.queryForObject("SELECT merchant_reference FROM payment_event_projections WHERE consumer_name=?",String.class,"shadow-"+run);
                try { executor.submit(() -> jdbc.update("UPDATE payment_event_projections SET merchant_reference='fresh drift' WHERE consumer_name=?","shadow-"+run)).get(10,TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                assertThatThrownBy(() -> preservation.preserve(planId,command(),"operator")).isInstanceOf(ProjectionPurgePlanConflictException.class);
                assertThat(jdbc.queryForObject("SELECT merchant_reference FROM payment_event_projections WHERE consumer_name=?",String.class,"shadow-"+run)).isEqualTo(original);
            });
        }
    }

    @Test
    void v13UpgradePreservesAcceptedPlanReplayAndSupportsNewPreservationAndApproval() throws Exception {
        String schema="preservation_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("preservation_upgrade_[0-9a-f]{32}");
        try (var connection=dataSource.getConnection()) {
            String original=connection.getSchema();
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var isolated=new JdbcTemplate(ds);
            var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            isolated.execute("CREATE SCHEMA \""+schema+"\"");
            try {
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("13").load().migrate();
                connection.setSchema(schema);
                var buildRepo=new ProjectionRebuildRepository(isolated);
                var routeRepo=new ProjectionRoutingRepository(isolated);
                var routingService=new ProjectionRoutingService(routeRepo,buildRepo,new ProjectionReconciliationRepository(isolated),kafka,"primary",properties);
                UUID target=UUID.randomUUID();
                tx.executeWithoutResult(status -> {
                    buildRepo.insertRun(target,"shadow-"+target,"primary",kafka.topic(),"builder","upgrade fixture");
                    buildRepo.beginGeneration(target,0,target,null,"builder","upgrade fixture",new ReconciliationGapContext(0,0,0,0));
                    buildRepo.finishPlan(target,0);
                });
                tx.executeWithoutResult(status -> routingService.promote(new ProjectionPromotionCommand(UUID.randomUUID(),target,0,0,"promote"),"operator"));
                tx.executeWithoutResult(status -> routingService.rollback(new ProjectionRollbackCommand(UUID.randomUUID(),1,"rollback"),"operator"));
                tx.executeWithoutResult(status -> routingService.retire(new ProjectionRetirementCommand(UUID.randomUUID(),target,2,"retire"),"operator"));
                var inventoryRepo=new ProjectionPurgePlanRepository(isolated,mapper);
                var evidenceRepo=spy(new ProjectionEvidenceRepository(isolated));
                doReturn(Instant.now().plus(31,ChronoUnit.DAYS)).when(evidenceRepo).observationTime();
                var policy=new ProjectionEvidenceService(evidenceRepo,routeRepo,properties,kafka,"primary");
                var planService=new ProjectionPurgePlanService(inventoryRepo,evidenceRepo,policy,routeRepo,properties,kafka,"primary");
                var intent=new ProjectionPurgePlanCommand(UUID.randomUUID(),target,3,"v13 accepted plan");
                var old=tx.execute(status -> planService.create(intent,"operator")).plan();
                String before=isolated.queryForObject("SELECT to_jsonb(p)::text FROM projection_purge_plans p",String.class);
                org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
                connection.setSchema(schema);
                assertThat(isolated.queryForObject("SELECT to_jsonb(p)::text FROM projection_purge_plans p",String.class)).isEqualTo(before);
                assertThat(tx.execute(status -> planService.create(intent,"operator")).plan()).isEqualTo(old);
                var service=new ProjectionPreservationService(new ProjectionPreservationRepository(isolated),inventoryRepo,
                        planService,evidenceRepo,routeRepo,verifier,new ProjectionApprovalProperties(60));
                var p=tx.execute(status -> service.preserve(old.requestId(),command(),"operator")).preservation();
                var approved=tx.execute(status -> service.approve(old.requestId(),approval(p),"operator"));
                assertThat(approved.approval().exportSha256()).isEqualTo(p.sha256());
                assertThat(approved.approval().deletionPermitted()).isFalse();
            } finally {
                connection.setSchema(original);
                isolated.execute("DROP SCHEMA \""+schema+"\" CASCADE");
            }
        }
    }

    private void changeRoute() {
        UUID next=UUID.randomUUID(); rebuild.create(next,"builder","new target"); rebuild.step(next,100);
        routing.promote(new ProjectionPromotionCommand(UUID.randomUUID(),next,0,3,"later route"),"operator");
    }
    private void replan() { planId=plans.create(new ProjectionPurgePlanCommand(UUID.randomUUID(),run,3,"larger inventory"),"operator").plan().requestId(); }
    private Command command() { return new Command(UUID.randomUUID(),3,"preserve complete evidence"); }
    private ApprovalCommand approval(ProjectionPreservation p) { return new ApprovalCommand(UUID.randomUUID(),p.requestId(),3,p.sha256(),true,"reviewed preserved evidence"); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }
    private java.util.Map<String,String> image() {
        var result=new java.util.LinkedHashMap<String,String>();
        for (String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename NOT IN ('flyway_schema_history','projection_preservations','projection_preservation_approvals')",String.class))
            result.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM "+table+" t",String.class));
        return result;
    }
    private void addLargeReceipt(int size,String character) {
        jdbc.update("""
                WITH identity AS (SELECT gen_random_uuid() AS event_id,gen_random_uuid() AS payment_id)
                INSERT INTO consumed_payment_events(consumer_name,event_id,payment_id,payment_version,event_type,schema_version,payload)
                SELECT ?,i.event_id,i.payment_id,c.payment_version,c.event_type,c.schema_version,
                    (jsonb_set(jsonb_set(jsonb_set(c.payload::jsonb,'{eventId}',to_jsonb(i.event_id::text)),
                    '{paymentId}',to_jsonb(i.payment_id::text)),'{data,id}',to_jsonb(i.payment_id::text))
                    || jsonb_build_object('extension',repeat(?,?)))::text
                FROM identity i CROSS JOIN (SELECT * FROM consumed_payment_events WHERE consumer_name='payment-projection-v1' LIMIT 1) c
                ""","shadow-"+run,character,size);
    }
}
