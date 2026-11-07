package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.config.PaymentEventTransportProperties;
import com.fincore.payments.dto.PaymentReconciliation;
import com.fincore.payments.dto.ProjectionReconciliationReport;
import com.fincore.payments.dto.ReconciliationFinding;
import com.fincore.payments.exception.InvalidPaymentEventException;
import com.fincore.payments.repository.ProjectionReconciliationRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.fincore.payments.dto.ReconciliationFinding.*;

@Service
public class ProjectionReconciliationService {
    private final ProjectionReconciliationRepository repository;
    private final PaymentEventRecordDecoder decoder;
    private final KafkaEventProperties kafka;
    private final PaymentEventTransportProperties transport;
    private final String source;

    public ProjectionReconciliationService(ProjectionReconciliationRepository repository, PaymentEventRecordDecoder decoder,
            KafkaEventProperties kafka, PaymentEventTransportProperties transport,
            @Value("${fincore.kafka.source-name:primary}") String source) {
        this.repository = repository;
        this.decoder = decoder;
        this.kafka = kafka;
        this.transport = transport;
        this.source = source;
    }

    // Always own a bounded snapshot, even if called by code already in a different transaction.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public ProjectionReconciliationReport reconcile(UUID paymentId, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100.");
        if (paymentId != null && after != null) {
            throw new IllegalArgumentException("Payment filter and pagination cursor cannot be combined.");
        }
        String consumer = PaymentEventConsumer.CONSUMER_NAME;
        var snapshot = repository.snapshot(consumer, paymentId);
        var comparisons = repository.page(consumer, paymentId, after, limit);
        boolean hasMore = comparisons.size() > limit;
        List<PaymentReconciliation> rows = comparisons.stream().limit(limit).map(this::assess).toList();
        var gaps = repository.gaps(consumer, source, kafka.topic());
        return new ProjectionReconciliationReport(snapshot.observedAt(), source, kafka.topic(), consumer,
                transport.transport(), "NOT_CERTIFIED", "LOGICAL_CONSUMER_ACROSS_SOURCE_NAMESPACES", paymentId,
                snapshot.totalCandidates(), limit, after, hasMore,
                hasMore ? rows.getLast().paymentId() : null,
                rows.stream().anyMatch(row -> !row.findings().isEmpty()) ? "OBSERVATIONS_FOUND" : "NO_OBSERVATIONS_ON_PAGE",
                gaps, rows);
    }

    private PaymentReconciliation assess(ProjectionReconciliationRepository.Comparison row) {
        List<ReconciliationFinding> findings = new ArrayList<>();
        boolean validSource = false;
        if (row.sourceVersion() == null) {
            findings.add(SOURCE_PAYMENT_MISSING);
        } else {
            if (row.eventCount() == 0) findings.add(NO_SOURCE_EVENTS);
            if (row.firstVersion() != null && row.firstVersion() > 0) findings.add(HISTORY_STARTS_AFTER_ZERO);
            if (row.internalGaps()) findings.add(HISTORY_VERSION_GAPS);
            if (row.lastVersion() != null && row.lastVersion() > row.sourceVersion()) findings.add(SOURCE_EVENT_AHEAD);
            if (row.currentEventId() == null) findings.add(CURRENT_SOURCE_EVENT_MISSING);
            else {
                try {
                    decoder.decode(new ConsumerRecord<>(kafka.topic(), 0, 0, row.paymentId().toString(), row.currentPayload()));
                    validSource = true;
                } catch (InvalidPaymentEventException invalid) {
                    findings.add(CURRENT_SOURCE_EVENT_INVALID);
                }
                if (!row.sourceSnapshotMatches()) findings.add(CURRENT_SOURCE_SNAPSHOT_MISMATCH);
            }
            if (row.projectionVersion() == null) findings.add(PROJECTION_MISSING);
            else if (row.projectionVersion() < row.sourceVersion()) findings.add(PROJECTION_BEHIND);
            else if (row.projectionVersion() > row.sourceVersion()) findings.add(PROJECTION_AHEAD);
            else if (row.currentEventId() != null && !row.projectionMatches()) findings.add(PROJECTION_SNAPSHOT_MISMATCH);
        }
        if (row.missing() > 0) findings.add(SOURCE_RECEIPTS_MISSING);
        if (row.conflicting() > 0) findings.add(SOURCE_RECEIPT_CONFLICT);
        if (row.unmatched() > 0) findings.add(UNMATCHED_CONSUMER_RECEIPTS);
        if (row.projectionVersion() != null && !row.projectionReceiptMatches()) findings.add(PROJECTION_RECEIPT_MISMATCH);
        if (row.latestReceiptVersion() != null && (row.projectionVersion() == null
                || row.latestReceiptVersion() > row.projectionVersion())) findings.add(LATEST_RECEIPT_NOT_PROJECTED);
        // A candidate for a separately designed replay/rebuild, never authorization to mutate.
        boolean candidate = validSource && row.sourceSnapshotMatches() && row.conflicting() == 0;
        return new PaymentReconciliation(row.paymentId(), row.sourceVersion(), row.projectionVersion(), row.eventCount(),
                row.firstVersion(), row.lastVersion(), row.matched(), row.missing(), row.conflicting(), row.unmatched(),
                row.latestReceiptVersion(), row.pending(), row.dead(), row.contiguous(), row.currentEventId(), candidate, findings);
    }
}
