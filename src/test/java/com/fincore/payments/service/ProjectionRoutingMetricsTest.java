package com.fincore.payments.service;

import com.fincore.payments.dto.ProjectionRoutingResult;
import com.fincore.payments.exception.ProjectionComparisonBudgetException;
import com.fincore.payments.exception.ProjectionRoutingConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.fincore.payments.service.ProjectionRoutingMetrics.Action.*;
import static org.assertj.core.api.Assertions.*;

class ProjectionRoutingMetricsTest {
    @Test
    void measuresAcceptedReplayConflictBudgetAndFailureWithFixedLabels() {
        var nanos=new AtomicLong();
        var metrics=new ProjectionRoutingMetrics(() -> nanos.getAndAdd(10));
        metrics.measure(PROMOTE,() -> result(false));
        metrics.measure(PROMOTE,() -> result(true));
        assertThatThrownBy(() -> metrics.measure(PROMOTE,() -> { throw new ProjectionRoutingConflictException(); }))
                .isInstanceOf(ProjectionRoutingConflictException.class);
        assertThatThrownBy(() -> metrics.measure(PROMOTE,() -> { throw new ProjectionComparisonBudgetException(); }))
                .isInstanceOf(ProjectionComparisonBudgetException.class);
        var commitFailure=new IllegalStateException("deferred constraint commit failed");
        assertThatThrownBy(() -> metrics.measure(PROMOTE,() -> { throw commitFailure; })).isSameAs(commitFailure);
        var snapshot=metrics.snapshot();
        assertThat(snapshot.scope()).isEqualTo("THIS_PROCESS_HTTP_ROUTING_COMMANDS");
        assertThat(snapshot.operations()).hasSize(4);
        var operation=snapshot.operations().getFirst();
        assertThat(operation.attempts()).isEqualTo(5);
        assertThat(operation.inFlight()).isZero();
        assertThat(operation.accepted()).isEqualTo(1);
        assertThat(operation.replayed()).isEqualTo(1);
        assertThat(operation.conflicts()).isEqualTo(1);
        assertThat(operation.budgetRefused()).isEqualTo(1);
        assertThat(operation.failed()).isEqualTo(1);
        assertThat(operation.totalNanos()).isEqualTo(50);
        assertThat(operation.maxNanos()).isEqualTo(10);
        assertThat(new ProjectionRoutingMetrics().snapshot().operations()).allSatisfy(o -> assertThat(o.attempts()).isZero());
    }

    @Test
    void snapshotIncludesInFlightWorkAndConcurrentCompletionsWithoutLosingCounts() throws Exception {
        var metrics=new ProjectionRoutingMetrics();
        var entered=new CountDownLatch(8);
        var release=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(8)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0;i<8;i++) tasks.add(executor.submit(() -> metrics.measure(REPLACE,() -> {
                entered.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return result(false);
            })));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var active=metrics.snapshot().operations().get(2);
                assertThat(active.attempts()).isEqualTo(8);
                assertThat(active.inFlight()).isEqualTo(8);
                assertThat(active.accepted()).isZero();
            } finally { release.countDown(); }
            for (var task:tasks) task.get(10,TimeUnit.SECONDS);
        }
        var complete=metrics.snapshot().operations().get(2);
        assertThat(complete.attempts()).isEqualTo(8);
        assertThat(complete.inFlight()).isZero();
        assertThat(complete.accepted()).isEqualTo(8);
        assertThat(complete.totalNanos()).isGreaterThanOrEqualTo(complete.maxNanos());
    }

    private ProjectionRoutingResult result(boolean replay) {
        return new ProjectionRoutingResult(UUID.randomUUID(),"PROMOTE",1,"shadow-example","test-actor",
                "test",Instant.now(),replay);
    }
}
