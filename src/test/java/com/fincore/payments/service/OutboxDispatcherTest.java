package com.fincore.payments.service;

import com.fincore.payments.config.OutboxProperties;
import com.fincore.payments.exception.OutboxPublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Orchestration tests only; PostgreSQL integration tests exercise actual transactions and locks. */
@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {
    @Mock OutboxDeliveryWorker worker;
    @Mock OutboxFailureRecorder failures;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OutboxDispatcher(worker, failures,
                new OutboxProperties(false, 1000, 3, 5, 1000, 60000));
    }

    @Test
    void stopsImmediatelyWhenNoEventIsDue() {
        when(worker.dispatchNext()).thenReturn(false);

        assertThat(dispatcher.dispatchBatch()).isZero();

        verify(worker).dispatchNext();
        verifyNoInteractions(failures);
        verifyNoMoreInteractions(worker);
    }

    @Test
    void stopsOnIdleAndCountsOnlySuccessfulDeliveries() {
        when(worker.dispatchNext()).thenReturn(true, false);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        verify(worker, times(2)).dispatchNext();
        verifyNoInteractions(failures);
        verifyNoMoreInteractions(worker);
    }

    @Test
    void limitsWorkToConfiguredBatchSize() {
        when(worker.dispatchNext()).thenReturn(true);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);

        verify(worker, times(3)).dispatchNext();
        verifyNoInteractions(failures);
        verifyNoMoreInteractions(worker);
    }

    @Test
    void recordsPublishFailureBeforeTryingAnotherPaymentEvent() {
        UUID eventId = UUID.randomUUID();
        var failure = new OutboxPublishException(eventId, 2, new IllegalStateException("Local sink failure"));
        when(worker.dispatchNext()).thenThrow(failure).thenReturn(true, false);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        var calls = inOrder(worker, failures);
        calls.verify(worker).dispatchNext();
        calls.verify(failures).recordFailure(eventId, 2);
        calls.verify(worker, times(2)).dispatchNext();
        calls.verifyNoMoreInteractions();
    }

    @Test
    void failedAttemptsAlsoConsumeBatchSlots() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(worker.dispatchNext()).thenThrow(
                new OutboxPublishException(first, 0, new IllegalStateException("First sink failure")),
                new OutboxPublishException(second, 0, new IllegalStateException("Second sink failure")),
                new OutboxPublishException(third, 0, new IllegalStateException("Third sink failure")))
                .thenReturn(true);

        assertThat(dispatcher.dispatchBatch()).isZero();

        verify(worker, times(3)).dispatchNext();
        verify(failures).recordFailure(first, 0);
        verify(failures).recordFailure(second, 0);
        verify(failures).recordFailure(third, 0);
        verifyNoMoreInteractions(worker, failures);
    }

    @Test
    void staleFailureRecorderResultDoesNotStopLaterWork() {
        UUID eventId = UUID.randomUUID();
        when(worker.dispatchNext()).thenThrow(
                new OutboxPublishException(eventId, 1, new IllegalStateException("Local sink failure")))
                .thenReturn(true, false);
        when(failures.recordFailure(eventId, 1)).thenReturn(false);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        verify(failures).recordFailure(eventId, 1);
        verify(worker, times(3)).dispatchNext();
        verifyNoMoreInteractions(worker, failures);
    }

    @Test
    void unexpectedDatabaseOrAcknowledgementFailurePropagates() {
        var databaseFailure = new DataAccessResourceFailureException("Could not persist acknowledgement");
        when(worker.dispatchNext()).thenThrow(databaseFailure);

        assertThatThrownBy(dispatcher::dispatchBatch).isSameAs(databaseFailure);

        verify(worker).dispatchNext();
        verifyNoInteractions(failures);
        verifyNoMoreInteractions(worker);
    }

    @Test
    void failureRecorderErrorPropagatesWithoutContinuingBatch() {
        UUID eventId = UUID.randomUUID();
        when(worker.dispatchNext()).thenThrow(
                new OutboxPublishException(eventId, 1, new IllegalStateException("Local sink failure")));
        var retryFailure = new DataAccessResourceFailureException("Could not persist retry metadata");
        doThrow(retryFailure).when(failures).recordFailure(eventId, 1);

        assertThatThrownBy(dispatcher::dispatchBatch).isSameAs(retryFailure);

        verify(worker).dispatchNext();
        verify(failures).recordFailure(eventId, 1);
        verifyNoMoreInteractions(worker, failures);
    }
}
