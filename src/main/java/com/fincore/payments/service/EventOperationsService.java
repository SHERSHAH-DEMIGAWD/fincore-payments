package com.fincore.payments.service;

import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.dto.OperationalOutboxEvent;
import com.fincore.payments.dto.OutboxOverview;
import com.fincore.payments.dto.RequeueAuditView;
import com.fincore.payments.dto.RequeueCommand;
import com.fincore.payments.dto.RequeueResult;
import com.fincore.payments.exception.EventOperationConflictException;
import com.fincore.payments.exception.EventOperationNotFoundException;
import com.fincore.payments.repository.EventOperationsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventOperationsService {
    private final EventOperationsRepository repository;

    public EventOperationsService(EventOperationsRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RequeueResult requeue(RequeueCommand command) {
        validate(command);
        // Serialize operators for one event. A subsequent fresh statement at READ COMMITTED
        // sees the winning request's audit even when this transaction started before its commit.
        var event = repository.lockEvent(command.eventId())
                .orElseThrow(EventOperationNotFoundException::new);
        var previous = repository.findRequest(command.requestId());
        if (previous.isPresent()) {
            RequeueAuditView audit = previous.get();
            if (!matches(command, audit)) throw new EventOperationConflictException();
            return result(audit, true);
        }
        if (event.status() != OutboxStatus.DEAD || event.retryGeneration() != command.expectedGeneration()) {
            throw new EventOperationConflictException();
        }

        Instant requeuedAt = repository.requeue(command.eventId(), command.expectedGeneration());
        RequeueAuditView audit = new RequeueAuditView(command.requestId(), command.eventId(),
                command.expectedGeneration(), command.expectedGeneration() + 1,
                event.attempts(), event.cycleAttempts(), command.operator(), command.reason(), requeuedAt);
        // The request ID is global. Concurrent reuse for different events reaches this unique
        // insert through different row locks; rejection rolls back this event's reset as well.
        if (!repository.insertAudit(audit)) throw new EventOperationConflictException();
        return result(audit, false);
    }

    @Transactional(readOnly = true)
    public OutboxOverview overview() {
        return repository.overview();
    }

    @Transactional(readOnly = true)
    public List<OperationalOutboxEvent> events(OutboxStatus status, int limit) {
        validateLimit(limit);
        return repository.events(status, limit, PaymentEventConsumer.CONSUMER_NAME);
    }

    @Transactional(readOnly = true)
    public List<RequeueAuditView> requeues(UUID eventId, int limit) {
        if (eventId == null) throw new IllegalArgumentException("Event ID is required.");
        validateLimit(limit);
        if (!repository.eventExists(eventId)) throw new EventOperationNotFoundException();
        return repository.requeues(eventId, limit);
    }

    private static RequeueResult result(RequeueAuditView audit, boolean replayed) {
        return new RequeueResult(audit.requestId(), audit.eventId(), audit.resultingGeneration(),
                audit.requeuedAt(), replayed);
    }

    private static boolean matches(RequeueCommand command, RequeueAuditView audit) {
        return command.eventId().equals(audit.eventId())
                && command.expectedGeneration() == audit.expectedGeneration()
                && command.operator().equals(audit.operator()) && command.reason().equals(audit.reason());
    }

    private static void validate(RequeueCommand command) {
        if (command == null || command.requestId() == null || command.eventId() == null) {
            throw new IllegalArgumentException("Request ID and event ID are required.");
        }
        if (command.expectedGeneration() < 0 || command.expectedGeneration() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Expected generation must be nonnegative and incrementable.");
        }
        validateText(command.operator(), 100, "Operator");
        validateText(command.reason(), 500, "Reason");
    }

    private static void validateText(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException(label + " must be nonblank, within its length limit, and on one line.");
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100.");
    }
}
