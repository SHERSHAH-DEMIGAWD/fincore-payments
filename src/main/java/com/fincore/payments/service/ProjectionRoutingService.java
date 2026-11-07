package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.ProjectionOperationsProperties;
import com.fincore.payments.dto.*;
import com.fincore.payments.exception.ProjectionRoutingConflictException;
import com.fincore.payments.exception.ProjectionRoutingNotFoundException;
import com.fincore.payments.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProjectionRoutingService {
    private final ProjectionRoutingRepository routing;
    private final ProjectionRebuildRepository rebuild;
    private final ProjectionReconciliationRepository reconciliation;
    private final String source;
    private final String topic;
    private final ProjectionOperationsProperties properties;

    public ProjectionRoutingService(ProjectionRoutingRepository routing,ProjectionRebuildRepository rebuild,
            ProjectionReconciliationRepository reconciliation,KafkaEventProperties kafka,
            @Value("${fincore.kafka.source-name:primary}") String source,ProjectionOperationsProperties properties) {
        this.routing=routing; this.rebuild=rebuild; this.reconciliation=reconciliation;
        this.source=source; this.topic=kafka.topic();
        this.properties=properties;
    }

    @Transactional(readOnly=true)
    public ProjectionRoutingView status() { return routing.view(); }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=30)
    public ProjectionTargetView target(UUID runId) {
        if (runId==null) invalid();
        var run=scoped(runId);
        return new ProjectionTargetView(runId,run.consumerName(),run.generation(),routing.lifecycle(runId));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ProjectionRoutingResult promote(ProjectionPromotionCommand command,String actor) {
        if (command == null || command.runId() == null || command.expectedBuildGeneration() < 0) invalid();
        validate(command.requestId(),command.expectedRevision(),actor,command.reason());
        fence();
        var saved=routing.request(command.requestId());
        if (saved.isPresent()) {
            var audit=saved.get();
            scoped(audit.runId());
            if (!audit.action().equals("PROMOTE") || !audit.runId().equals(command.runId())
                    || audit.buildGeneration()!=command.expectedBuildGeneration()
                    || !same(audit,command.expectedRevision(),actor,command.reason())) conflict();
            return audit.result(true);
        }
        var state=routing.view();
        if (state.revision()!=command.expectedRevision() || !state.activeConsumer().equals(ProjectionRoutingRepository.BASELINE)
                || state.shadowRunId()!=null && !state.shadowRunId().equals(command.runId())) conflict();
        var run=scoped(command.runId());
        if (run.generation()!=command.expectedBuildGeneration()) conflict();
        routing.requireComparisonBudget(properties.maxComparisonRows(),run.runId());
        if (state.shadowRunId()==null) {
            candidate(run);
        }
        safe(run.consumerName());
        routing.switchRoute(command.requestId(),"PROMOTE",state.revision(),run.runId(),run.generation(),
                actor,command.reason(),run.consumerName());
        return routing.request(command.requestId()).orElseThrow().result(false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ProjectionRoutingResult replace(ProjectionReplacementCommand command,String actor) {
        if (command==null || command.runId()==null || command.expectedPreviousRunId()==null
                || command.expectedBuildGeneration()<0) invalid();
        validate(command.requestId(),command.expectedRevision(),actor,command.reason());
        fence();
        var saved=routing.request(command.requestId());
        if (saved.isPresent()) {
            var audit=saved.get();
            if (!audit.action().equals("REPLACE")) conflict();
            scoped(audit.runId());
            scoped(audit.previousRunId());
            if (!audit.runId().equals(command.runId())
                    || !java.util.Objects.equals(audit.previousRunId(),command.expectedPreviousRunId())
                    || audit.buildGeneration()!=command.expectedBuildGeneration()
                    || !same(audit,command.expectedRevision(),actor,command.reason())) conflict();
            return audit.result(true);
        }
        var state=routing.view();
        if (state.revision()!=command.expectedRevision()
                || !state.activeConsumer().equals(ProjectionRoutingRepository.BASELINE)
                || !command.expectedPreviousRunId().equals(state.shadowRunId())
                || command.runId().equals(state.shadowRunId())) conflict();
        var old=scoped(state.shadowRunId());
        var next=scoped(command.runId());
        if (next.generation()!=command.expectedBuildGeneration()) conflict();
        routing.requireComparisonBudget(properties.maxComparisonRows(),old.runId(),next.runId());
        candidate(next);
        safe(old.consumerName());
        safe(next.consumerName());
        routing.switchRoute(command.requestId(),"REPLACE",state.revision(),next.runId(),next.generation(),
                actor,command.reason(),next.consumerName());
        return routing.request(command.requestId()).orElseThrow().result(false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ProjectionRoutingResult retire(ProjectionRetirementCommand command,String actor) {
        if (command==null || command.runId()==null) invalid();
        validate(command.requestId(),command.expectedRevision(),actor,command.reason());
        fence();
        var saved=routing.request(command.requestId());
        if (saved.isPresent()) {
            var audit=saved.get();
            scoped(audit.runId());
            if (!audit.action().equals("RETIRE") || !audit.runId().equals(command.runId())
                    || !same(audit,command.expectedRevision(),actor,command.reason())) conflict();
            return audit.result(true);
        }
        var state=routing.view();
        if (state.revision()!=command.expectedRevision()
                || !state.activeConsumer().equals(ProjectionRoutingRepository.BASELINE)
                || !command.runId().equals(state.shadowRunId())) conflict();
        var run=scoped(command.runId());
        routing.requireComparisonBudget(properties.maxComparisonRows(),run.runId());
        safe(run.consumerName());
        routing.switchRoute(command.requestId(),"RETIRE",state.revision(),run.runId(),run.generation(),
                actor,command.reason(),ProjectionRoutingRepository.BASELINE);
        return routing.request(command.requestId()).orElseThrow().result(false);
    }

    private void candidate(ProjectionRebuildRepository.Run run) {
        if (!routing.lifecycle(run.runId()).equals("ISOLATED")) conflict();
        // Current fenced evidence owns this decision. A previous validate response is advisory.
        var view=rebuild.view(run.runId(),run.generation());
        var check=rebuild.validation(run.runId(),run.generation(),run.consumerName());
        if (!view.state().equals("BUILT") || view.pendingItems()!=0 || view.blockedItems()!=0
                || check.shadowMismatches()!=0 || check.unplannedShadowRows()!=0
                || check.sourceChanged()!=0 || check.sourceAdded()!=0 || check.sourceRemoved()!=0) conflict();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public ProjectionRoutingResult rollback(ProjectionRollbackCommand command,String actor) {
        if (command == null) invalid();
        validate(command.requestId(),command.expectedRevision(),actor,command.reason());
        fence();
        var saved=routing.request(command.requestId());
        if (saved.isPresent()) {
            var audit=saved.get();
            scoped(audit.runId());
            if (!audit.action().equals("ROLLBACK") || !same(audit,command.expectedRevision(),actor,command.reason())) conflict();
            return audit.result(true);
        }
        var state=routing.view();
        if (state.revision()!=command.expectedRevision() || state.shadowRunId()==null
                || state.activeConsumer().equals(ProjectionRoutingRepository.BASELINE)) conflict();
        var run=scoped(state.shadowRunId());
        routing.requireComparisonBudget(properties.maxComparisonRows(),run.runId());
        safe(run.consumerName());
        routing.switchRoute(command.requestId(),"ROLLBACK",state.revision(),run.runId(),run.generation(),
                actor,command.reason(),ProjectionRoutingRepository.BASELINE);
        return routing.request(command.requestId()).orElseThrow().result(false);
    }

    private void fence() {
        routing.fence();
    }

    private void safe(String shadow) {
        var gaps=reconciliation.gaps(PaymentEventConsumer.CONSUMER_NAME,source,topic);
        if (gaps.configuredOpenQuarantines()>0 || gaps.configuredOtherOpenFailures()>0
                || gaps.otherScopeOpenQuarantines()>0 || gaps.otherScopeOtherOpenFailures()>0
                || !routing.equivalent(shadow) || !routing.currentSourceMatches(shadow)
                || !routing.currentSourceMatches(ProjectionRoutingRepository.BASELINE)) conflict();
    }

    private ProjectionRebuildRepository.Run scoped(UUID runId) {
        var run=rebuild.findRun(runId,false).orElseThrow(ProjectionRoutingNotFoundException::new);
        if (!source.equals(run.sourceName()) || !topic.equals(run.topic())) throw new ProjectionRoutingNotFoundException();
        return run;
    }

    private static boolean same(ProjectionRoutingRepository.Audit audit,long expected,String actor,String reason) {
        return audit.expectedRevision()==expected && audit.actor().equals(actor) && audit.reason().equals(reason);
    }
    private static void validate(UUID request,long expected,String actor,String reason) {
        if (request==null || expected<0 || expected==Long.MAX_VALUE) invalid();
        text(actor,100); text(reason,500);
    }
    private static void text(String value,int max) {
        if (value==null || value.isBlank() || value.length()>max || value.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c)==Character.LINE_SEPARATOR
                        || Character.getType(c)==Character.PARAGRAPH_SEPARATOR)) invalid();
    }
    private static void invalid() { throw new IllegalArgumentException("Routing requires bounded identity, revision and single-line reason."); }
    private static void conflict() { throw new ProjectionRoutingConflictException(); }
}
