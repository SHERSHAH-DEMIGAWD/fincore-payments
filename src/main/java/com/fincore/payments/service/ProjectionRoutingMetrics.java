package com.fincore.payments.service;

import com.fincore.payments.dto.ProjectionRoutingResult;
import com.fincore.payments.exception.ProjectionComparisonBudgetException;
import com.fincore.payments.exception.ProjectionRoutingConflictException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Fixed-cardinality, process-local HTTP diagnostics measured outside the transaction proxy. */
@Component
public class ProjectionRoutingMetrics {
    public enum Action { PROMOTE, ROLLBACK, REPLACE, RETIRE }
    private enum Outcome { ACCEPTED, REPLAYED, CONFLICT, BUDGET_REFUSED, FAILED }
    private final EnumMap<Action,Counter> counters=new EnumMap<>(Action.class);
    private final LongSupplier nanos;
    private final Instant since=Instant.now();

    public ProjectionRoutingMetrics() { this(System::nanoTime); }
    ProjectionRoutingMetrics(LongSupplier nanos) {
        this.nanos=nanos;
        for (var action:Action.values()) counters.put(action,new Counter());
    }

    public ProjectionRoutingResult measure(Action action,Supplier<ProjectionRoutingResult> operation) {
        long started=nanos.getAsLong();
        begin(action);
        Outcome outcome=Outcome.FAILED;
        try {
            var result=operation.get(); // The proxied service returns after commit, including deferred checks.
            outcome=result.replayed() ? Outcome.REPLAYED : Outcome.ACCEPTED;
            return result;
        } catch (ProjectionComparisonBudgetException failure) {
            outcome=Outcome.BUDGET_REFUSED;
            throw failure;
        } catch (ProjectionRoutingConflictException failure) {
            outcome=Outcome.CONFLICT;
            throw failure;
        } finally {
            finish(action,outcome,Math.max(0,nanos.getAsLong()-started));
        }
    }

    private synchronized void begin(Action action) {
        var counter=counters.get(action);
        counter.attempts=add(counter.attempts,1);
        counter.inFlight++;
    }

    private synchronized void finish(Action action,Outcome outcome,long elapsed) {
        var c=counters.get(action);
        c.inFlight--;
        switch (outcome) {
            case ACCEPTED -> c.accepted=add(c.accepted,1);
            case REPLAYED -> c.replayed=add(c.replayed,1);
            case CONFLICT -> c.conflicts=add(c.conflicts,1);
            case BUDGET_REFUSED -> c.budgetRefused=add(c.budgetRefused,1);
            case FAILED -> c.failed=add(c.failed,1);
        }
        c.totalNanos=add(c.totalNanos,elapsed);
        c.maxNanos=Math.max(c.maxNanos,elapsed);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(since,"THIS_PROCESS_HTTP_ROUTING_COMMANDS",counters.entrySet().stream().map(entry -> {
            var c=entry.getValue();
            return new Operation(entry.getKey(),c.attempts,c.inFlight,c.accepted,c.replayed,c.conflicts,
                    c.budgetRefused,c.failed,c.totalNanos,c.maxNanos);
        }).toList());
    }

    private static long add(long value,long delta) { return Long.MAX_VALUE-value<delta ? Long.MAX_VALUE : value+delta; }
    private static class Counter {
        long attempts,inFlight,accepted,replayed,conflicts,budgetRefused,failed,totalNanos,maxNanos;
    }
    public record Snapshot(Instant since,String scope,List<Operation> operations) { }
    public record Operation(Action action,long attempts,long inFlight,long accepted,long replayed,long conflicts,
            long budgetRefused,long failed,long totalNanos,long maxNanos) { }
}
