package com.fincore.payments.service;

import com.fincore.payments.config.KafkaEventProperties;
import com.fincore.payments.dto.ConsumerGapReport;
import com.fincore.payments.dto.ConsumerQuarantineView;
import com.fincore.payments.dto.ConsumerRecoveryCommand;
import com.fincore.payments.dto.ConsumerRecoveryResult;
import com.fincore.payments.exception.ConsumerRecoveryConflictException;
import com.fincore.payments.exception.ConsumerRecoveryNotFoundException;
import com.fincore.payments.repository.ConsumerFailureRepository;
import com.fincore.payments.repository.ConsumerRecoveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ConsumerRecoveryService {
    private final ConsumerRecoveryRepository recovery;
    private final ConsumerFailureRepository failures;
    private final PaymentEventRecordDecoder decoder;
    private final PaymentEventConsumer consumer;
    private final String source;
    private final String topic;

    public ConsumerRecoveryService(ConsumerRecoveryRepository recovery, ConsumerFailureRepository failures,
            PaymentEventRecordDecoder decoder, PaymentEventConsumer consumer, KafkaEventProperties kafka,
            @Value("${fincore.kafka.source-name:primary}") String source) {
        this.recovery = recovery;
        this.failures = failures;
        this.decoder = decoder;
        this.consumer = consumer;
        this.source = source;
        this.topic = kafka.topic();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConsumerRecoveryResult quarantine(ConsumerRecoveryCommand command) {
        return apply(command, "QUARANTINE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConsumerRecoveryResult replay(ConsumerRecoveryCommand command) {
        return apply(command, "REPLAY");
    }

    private ConsumerRecoveryResult apply(ConsumerRecoveryCommand command, String action) {
        validate(command);
        // Verify scope even for an exact replay; changing namespace/topic never broadens access.
        var evidence = evidence(command);
        var existing = replayRequest(command, action);
        if (existing.isPresent()) return existing.get();
        failures.lockPosition(source, PaymentEventConsumer.CONSUMER_NAME, evidence.record());
        // Another attempt may have completed while we waited on the common position lock.
        existing = replayRequest(command, action);
        if (existing.isPresent()) return existing.get();
        evidence = evidence(command);
        if (!evidence.fingerprint().equals(command.expectedFingerprint()) || evidence.processed() || evidence.resolved()) {
            throw new ConsumerRecoveryConflictException();
        }
        if (action.equals("QUARANTINE")) {
            if (!Set.of("INVALID_EVENT", "EVENT_CONFLICT").contains(evidence.errorCode())
                    || recovery.hasAction(command.failureId(), "QUARANTINE")) {
                throw new ConsumerRecoveryConflictException();
            }
        } else {
            if (!recovery.hasAction(command.failureId(), "QUARANTINE")
                    || recovery.hasAction(command.failureId(), "REPLAY")) {
                throw new ConsumerRecoveryConflictException();
            }
            // Only the immutable original evidence is replayed, through the live wire contract.
            // REQUIRED processing, success markers and audit all roll back if any part fails.
            consumer.consume(decoder.decode(evidence.record()));
            failures.resolve(source, PaymentEventConsumer.CONSUMER_NAME, evidence.record());
        }
        if (!recovery.insert(command, action)) throw new ConsumerRecoveryConflictException();
        return recovery.findRequest(command.requestId()).orElseThrow().result(false);
    }

    private ConsumerRecoveryRepository.FailureEvidence evidence(ConsumerRecoveryCommand command) {
        return recovery.findFailure(command.failureId(), source, PaymentEventConsumer.CONSUMER_NAME, topic)
                .orElseThrow(ConsumerRecoveryNotFoundException::new);
    }

    private Optional<ConsumerRecoveryResult> replayRequest(ConsumerRecoveryCommand command, String action) {
        return recovery.findRequest(command.requestId()).map(audit -> {
            if (!audit.command().equals(command) || !audit.action().equals(action)) {
                throw new ConsumerRecoveryConflictException();
            }
            return audit.result(true);
        });
    }

    @Transactional(readOnly = true)
    public List<ConsumerQuarantineView> quarantines(boolean openOnly, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100.");
        return recovery.quarantines(source, PaymentEventConsumer.CONSUMER_NAME, topic, openOnly, limit);
    }

    @Transactional(readOnly = true)
    public ConsumerGapReport gaps() { return recovery.gaps(source, PaymentEventConsumer.CONSUMER_NAME, topic); }

    private static void validate(ConsumerRecoveryCommand command) {
        if (command == null || command.requestId() == null || command.failureId() == null
                || command.expectedFingerprint() == null || !command.expectedFingerprint().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Recovery requires IDs and a lowercase SHA-256 fingerprint.");
        }
        text(command.operator(), 100);
        text(command.reason(), 500);
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.LINE_SEPARATOR
                        || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("Operator and reason must be bounded nonblank single-line text.");
        }
    }
}
