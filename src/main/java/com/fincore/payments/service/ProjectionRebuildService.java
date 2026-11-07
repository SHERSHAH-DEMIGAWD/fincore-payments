package com.fincore.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.ReconciliationGapContext;
import com.fincore.payments.dto.ShadowRebuildPlanResult;
import com.fincore.payments.dto.ShadowRebuildStepResult;
import com.fincore.payments.dto.ShadowRebuildValidation;
import com.fincore.payments.dto.ShadowRebuildView;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.exception.ShadowRebuildConflictException;
import com.fincore.payments.exception.ShadowRebuildNotFoundException;
import com.fincore.payments.repository.PaymentEventConsumerRepository;
import com.fincore.payments.repository.ProjectionRebuildRepository;
import com.fincore.payments.repository.ProjectionReconciliationRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Materializes isolated latest-state inputs. Enrollment freezes rebuilding and hands updates to live fanout. */
@Service
public class ProjectionRebuildService {
    private final ProjectionRebuildRepository repository;
    private final PaymentEventConsumerRepository receipts;
    private final ProjectionReconciliationRepository reconciliation;
    private final PaymentEventRecordDecoder decoder;
    private final ObjectMapper mapper;
    private final String source;
    private final String topic;
    private final TransactionTemplate planning;

    public ProjectionRebuildService(ProjectionRebuildRepository repository,PaymentEventConsumerRepository receipts,
            ProjectionReconciliationRepository reconciliation,PaymentEventRecordDecoder decoder,ObjectMapper mapper,
            KafkaEventProperties kafka,@Value("${fincore.kafka.source-name:primary}") String source,
            PlatformTransactionManager transactions) {
        this.repository = repository;
        this.receipts = receipts;
        this.reconciliation = reconciliation;
        this.decoder = decoder;
        this.mapper = mapper;
        this.source = source;
        this.topic = kafka.topic();
        planning = new TransactionTemplate(transactions);
        planning.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        planning.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        planning.setTimeout(60);
    }

    public ShadowRebuildPlanResult create(UUID runId,String operator,String reason) {
        command(runId,operator,reason);
        return retryPlanning(() -> planning.execute(transaction -> {
            receipts.lockRouting();
            var previous = repository.findRun(runId,false);
            if (previous.isPresent()) {
                var run = scoped(previous.get());
                if (!run.operator().equals(operator) || !run.reason().equals(reason)) conflict();
                return repository.findRequest(runId).orElseThrow().result(true);
            }
            String consumer = "shadow-" + runId;
            if (!repository.insertRun(runId,consumer,source,topic,operator,reason)) {
                // REPEATABLE READ normally raises 40001 for a concurrent winner. A missing row
                // is never treated as permission to publish a second partial plan.
                throw new ShadowRebuildConflictException();
            }
            if (repository.identityPopulated(consumer)) conflict();
            if (!repository.beginGeneration(runId,0,runId,null,operator,reason,gaps(source,topic))) conflict();
            plan(runId,0);
            return repository.findRequest(runId).orElseThrow().result(false);
        }));
    }

    public ShadowRebuildPlanResult catchUp(UUID runId,long expectedGeneration,UUID requestId,String operator,String reason) {
        command(runId,operator,reason);
        if (requestId == null || expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Catch-up requires request identity and an incrementable expected generation.");
        }
        return retryPlanning(() -> planning.execute(transaction -> {
            receipts.lockRouting();
            scoped(repository.findRun(runId,false).orElseThrow(ShadowRebuildNotFoundException::new));
            var original = repository.findRequest(requestId);
            if (original.isPresent()) {
                var saved = original.get();
                if (!saved.runId().equals(runId) || !Objects.equals(saved.expectedGeneration(),expectedGeneration)
                        || !saved.operator().equals(operator) || !saved.reason().equals(reason)) conflict();
                return saved.result(true);
            }
            var run = scoped(repository.findRun(runId,true).orElseThrow(ShadowRebuildNotFoundException::new));
            if (receipts.isEverEnrolledRun(runId)) conflict();
            if (run.generation() != expectedGeneration || repository.view(runId,expectedGeneration).pendingItems() != 0) conflict();
            long generation = expectedGeneration + 1;
            if (!repository.beginGeneration(runId,generation,requestId,expectedGeneration,operator,reason,gaps(source,topic))) conflict();
            repository.advanceGeneration(runId,generation);
            plan(runId,generation);
            return repository.findRequest(requestId).orElseThrow().result(false);
        }));
    }

    private void plan(UUID runId,long generation) {
        repository.materialize(runId,generation);
        List<ProjectionRebuildRepository.Item> inputs;
        do {
            inputs = repository.items(runId,generation,"UNVALIDATED",100);
            for (var item : inputs) {
                String block = null;
                String canonical = null;
                if (item.eventId() == null) block = "NO_CURRENT_SOURCE_EVENT";
                else {
                    try {
                        var event = decode(item);
                        if (!item.sourceMatches()) block = "SOURCE_SNAPSHOT_MISMATCH";
                        else canonical = serialize(event);
                    } catch (InvalidPaymentEventException invalid) { block = "INVALID_SOURCE_EVENT"; }
                }
                repository.classify(runId,generation,item.paymentId(),canonical,block);
            }
        } while (!inputs.isEmpty());
        repository.finishPlan(runId,generation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,isolation = Isolation.READ_COMMITTED,timeout = 30)
    public ShadowRebuildStepResult step(UUID runId,int limit) {
        if (runId == null || limit < 1 || limit > 100) throw new IllegalArgumentException("Step requires a run and limit from 1 to 100.");
        receipts.lockRouting();
        var run = scoped(repository.findRun(runId,true).orElseThrow(ShadowRebuildNotFoundException::new));
        if (receipts.isEverEnrolledRun(runId)) conflict();
        var items = repository.items(runId,run.generation(),"PENDING",limit);
        for (var item : items) {
            PaymentEvent event = decode(item);
            String canonical = serialize(event);
            if (!repository.canonicalMatches(runId,run.generation(),item.paymentId(),canonical)) conflict();
            if (receipts.insertReceipt(run.consumerName(),event,canonical)) {
                receipts.applyProjection(run.consumerName(),event);
            } else if (!receipts.matchesExistingReceipt(run.consumerName(),event,canonical)) conflict();
            if (!repository.projectionMatches(runId,run.generation(),item.paymentId(),run.consumerName())) conflict();
            repository.markApplied(runId,run.generation(),item.paymentId());
        }
        if (!items.isEmpty()) repository.finishStep(runId,run.generation());
        return new ShadowRebuildStepResult(runId,run.generation(),items.size(),repository.view(runId,run.generation()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,readOnly = true,isolation = Isolation.REPEATABLE_READ,timeout = 30)
    public ShadowRebuildView status(UUID runId) {
        var run = scoped(repository.findRun(required(runId),false).orElseThrow(ShadowRebuildNotFoundException::new));
        return repository.view(runId,run.generation());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,readOnly = true,isolation = Isolation.REPEATABLE_READ,timeout = 30)
    public ShadowRebuildValidation validate(UUID runId) {
        var run = scoped(repository.findRun(required(runId),false).orElseThrow(ShadowRebuildNotFoundException::new));
        var view = repository.view(runId,run.generation());
        var compared = repository.validation(runId,run.generation(),run.consumerName());
        var gaps = gaps(run.sourceName(),run.topic());
        String state = view.pendingItems() > 0 ? "BUILD_INCOMPLETE"
                : view.blockedItems() > 0 ? "BLOCKED_INPUTS"
                : compared.shadowMismatches() > 0 || compared.unplannedShadowRows() > 0 ? "SHADOW_MISMATCH"
                : compared.sourceChanged() > 0 || compared.sourceAdded() > 0 || compared.sourceRemoved() > 0 ? "SOURCE_CHANGED"
                : openGaps(gaps) ? "MATCHES_CURRENT_SOURCE_WITH_OPEN_GAPS" : "MATCHES_CURRENT_SOURCE";
        return new ShadowRebuildValidation(runId,run.generation(),run.consumerName(),compared.observedAt(),state,
                "NOT_CERTIFIED",switch (receipts.targetLifecycle(runId)) {
                    case "MAINTAINED" -> "ENROLLED_LIVE_FANOUT";
                    case "RETIRED" -> "RETIRED_EVIDENCE_RETAINED";
                    default -> "REQUIRES_AUTHENTICATED_PROMOTION";
                },
                view.totalItems(),view.pendingItems(),view.blockedItems(),
                compared.shadowMismatches(),compared.sourceChanged(),compared.sourceAdded(),compared.sourceRemoved(),
                compared.unplannedShadowRows(),view.historyIncompleteItems(),gaps);
    }

    private ProjectionRebuildRepository.Run scoped(ProjectionRebuildRepository.Run run) {
        if (!run.sourceName().equals(source) || !run.topic().equals(topic)) throw new ShadowRebuildNotFoundException();
        return run;
    }

    private ReconciliationGapContext gaps(String sourceName,String sourceTopic) {
        return reconciliation.gaps(PaymentEventConsumer.CONSUMER_NAME,sourceName,sourceTopic);
    }

    private PaymentEvent decode(ProjectionRebuildRepository.Item item) {
        return decoder.decode(new ConsumerRecord<>(topic,0,0,item.paymentId().toString(),item.originalPayload()));
    }

    private String serialize(PaymentEvent event) {
        try { return mapper.writeValueAsString(event); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not serialize shadow input contract.",exception); }
    }

    private static boolean openGaps(ReconciliationGapContext gaps) {
        return gaps.configuredOpenQuarantines() > 0 || gaps.configuredOtherOpenFailures() > 0
                || gaps.otherScopeOpenQuarantines() > 0 || gaps.otherScopeOtherOpenFailures() > 0;
    }

    private static <T> T retryPlanning(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return action.get(); }
            catch (RuntimeException failure) {
                if (attempt >= 2 || !serializationFailure(failure)) throw failure;
            }
        }
    }

    private static boolean serializationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    private static UUID required(UUID runId) {
        if (runId == null) throw new IllegalArgumentException("Run identity is required.");
        return runId;
    }

    private static void command(UUID runId,String operator,String reason) {
        required(runId);
        text(operator,100);
        text(reason,500);
    }

    private static void text(String text,int max) {
        if (text == null || text.isBlank() || text.length() > max || text.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.LINE_SEPARATOR
                        || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("Operator and reason must be bounded nonblank single-line text.");
        }
    }

    private static void conflict() { throw new ShadowRebuildConflictException(); }
}
