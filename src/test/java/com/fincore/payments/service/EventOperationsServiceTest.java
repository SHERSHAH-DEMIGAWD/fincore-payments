package com.fincore.payments.service;

import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.dto.RequeueAuditView;
import com.fincore.payments.dto.RequeueCommand;
import com.fincore.payments.dto.RequeueResult;
import com.fincore.payments.exception.EventOperationConflictException;
import com.fincore.payments.exception.EventOperationNotFoundException;
import com.fincore.payments.repository.EventOperationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Real database integration tests additionally check rollback, row locks, and deferred audits. */
@ExtendWith(MockitoExtension.class)
class EventOperationsServiceTest {
    @Mock EventOperationsRepository repository;
    private EventOperationsService operations;
    private final UUID requestId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final Instant requeuedAt = Instant.parse("2026-10-22T10:00:00Z");

    @BeforeEach
    void setUp() {
        operations = new EventOperationsService(repository);
    }

    @Test
    void requeuesDeadEventAndRecordsThePreviousCounters() {
        var command = command();
        var audit = audit();
        when(repository.lockEvent(eventId)).thenReturn(Optional.of(
                new EventOperationsRepository.EventState(OutboxStatus.DEAD, 10, 5, 1)));
        when(repository.findRequest(requestId)).thenReturn(Optional.empty());
        when(repository.requeue(eventId, 1)).thenReturn(requeuedAt);
        when(repository.insertAudit(audit)).thenReturn(true);

        assertThat(operations.requeue(command))
                .isEqualTo(new RequeueResult(requestId, eventId, 2, requeuedAt, false));

        var calls = inOrder(repository);
        calls.verify(repository).lockEvent(eventId);
        calls.verify(repository).findRequest(requestId);
        calls.verify(repository).requeue(eventId, 1);
        calls.verify(repository).insertAudit(audit);
        calls.verifyNoMoreInteractions();
    }

    @Test
    void exactReplayReturnsOriginalResultEvenAfterDeliveryOrNewerGenerations() {
        when(repository.lockEvent(eventId)).thenReturn(Optional.of(
                new EventOperationsRepository.EventState(OutboxStatus.DELIVERED, 16, 1, 3)));
        when(repository.findRequest(requestId)).thenReturn(Optional.of(audit()));

        assertThat(operations.requeue(command()))
                .isEqualTo(new RequeueResult(requestId, eventId, 2, requeuedAt, true));

        verify(repository).lockEvent(eventId);
        verify(repository).findRequest(requestId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void reusedRequestIdWithDifferentReasonConflictsBeforeChangingState() {
        when(repository.lockEvent(eventId)).thenReturn(Optional.of(
                new EventOperationsRepository.EventState(OutboxStatus.DEAD, 10, 5, 1)));
        when(repository.findRequest(requestId)).thenReturn(Optional.of(audit()));

        assertThatThrownBy(() -> operations.requeue(
                new RequeueCommand(requestId, eventId, 1, "local-operator", "A different reason")))
                .isInstanceOf(EventOperationConflictException.class);

        verify(repository).lockEvent(eventId);
        verify(repository).findRequest(requestId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingEventFailsWithoutLookingUpOrWritingAudits() {
        when(repository.lockEvent(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.requeue(command()))
                .isInstanceOf(EventOperationNotFoundException.class);

        verify(repository).lockEvent(eventId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void wrongGenerationConflicts() {
        when(repository.lockEvent(eventId)).thenReturn(Optional.of(
                new EventOperationsRepository.EventState(OutboxStatus.DEAD, 15, 5, 2)));
        when(repository.findRequest(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.requeue(command()))
                .isInstanceOf(EventOperationConflictException.class);

        verify(repository).lockEvent(eventId);
        verify(repository).findRequest(requestId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void pendingEventCannotBeResetByAnOperator() {
        when(repository.lockEvent(eventId)).thenReturn(Optional.of(
                new EventOperationsRepository.EventState(OutboxStatus.PENDING, 10, 0, 1)));
        when(repository.findRequest(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.requeue(command()))
                .isInstanceOf(EventOperationConflictException.class);

        verify(repository).lockEvent(eventId);
        verify(repository).findRequest(requestId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void auditIdentityConflictPropagatesSoTheTransactionCannotCommit() {
        when(repository.lockEvent(eventId)).thenReturn(Optional.of(
                new EventOperationsRepository.EventState(OutboxStatus.DEAD, 10, 5, 1)));
        when(repository.findRequest(requestId)).thenReturn(Optional.empty());
        when(repository.requeue(eventId, 1)).thenReturn(requeuedAt);
        when(repository.insertAudit(audit())).thenReturn(false);

        assertThatThrownBy(() -> operations.requeue(command()))
                .isInstanceOf(EventOperationConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "line\nfeed", "tab\tcharacter", "bad\u0000character",
            "line\u2028separator", "paragraph\u2029separator"})
    void invalidOperatorIsRejectedBeforeDatabaseAccess(String operator) {
        assertThatThrownBy(() -> operations.requeue(
                new RequeueCommand(requestId, eventId, 1, operator, "Broker restored")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void overlongReasonAndUnincrementableGenerationAreRejected() {
        assertThatThrownBy(() -> operations.requeue(
                new RequeueCommand(requestId, eventId, 1, "local-operator", "x".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.requeue(
                new RequeueCommand(requestId, eventId, Long.MAX_VALUE, "local-operator", "Broker restored")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    void listLimitsAreBoundedBeforeDatabaseAccess(int limit) {
        assertThatThrownBy(() -> operations.events(null, limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.requeues(eventId, limit))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private RequeueCommand command() {
        return new RequeueCommand(requestId, eventId, 1, "local-operator", "Broker restored");
    }

    private RequeueAuditView audit() {
        return new RequeueAuditView(requestId, eventId, 1, 2, 10, 5,
                "local-operator", "Broker restored", requeuedAt);
    }
}
