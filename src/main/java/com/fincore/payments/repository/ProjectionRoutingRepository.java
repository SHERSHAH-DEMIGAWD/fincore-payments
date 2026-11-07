package com.fincore.payments.repository;

import com.fincore.payments.dto.ProjectionRoutingResult;
import com.fincore.payments.dto.ProjectionRoutingView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectionRoutingRepository {
    // One database-wide logical read model. All consumers use its shared transaction fence.
    public static final long FENCE = 742190310L;
    public static final String BASELINE = "payment-projection-v1";
    private final JdbcTemplate jdbc;

    public ProjectionRoutingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void fence() {
        try {
        // A busy consumer/route change is a reviewable conflict, not an unbounded operator wait.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, FENCE))) {
            throw new com.fincore.payments.exception.ProjectionRoutingConflictException();
        }
        // NOWAIT prevents cycles with position-first recovery and source/outbox writers.
        // Reads begin only after every table is frozen; READ COMMITTED sees their latest commits.
        jdbc.execute("""
                LOCK TABLE payments, payment_outbox, projection_rebuild_runs, projection_rebuild_generations,
                    projection_rebuild_items, consumer_event_positions, consumer_event_failures,
                    consumer_recovery_audit, consumed_payment_events, payment_event_projections
                IN SHARE MODE NOWAIT
                """);
        } catch (DataAccessException failure) { throw translateLock(failure); }
    }

    public ProjectionRoutingView view() {
        return jdbc.query("SELECT * FROM projection_routes", (r,n) -> new ProjectionRoutingView(
                r.getLong("revision"),r.getString("active_consumer"),r.getObject("shadow_run_id",UUID.class),
                r.getObject("shadow_run_id",UUID.class)==null ? null : "shadow-" + r.getObject("shadow_run_id",UUID.class)))
                .stream().findFirst().orElse(new ProjectionRoutingView(0,BASELINE,null,null));
    }

    public String lifecycle(UUID runId) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN NOT r.routing_enrolled THEN 'ISOLATED'
                    WHEN EXISTS (SELECT 1 FROM projection_routes p WHERE p.shadow_run_id=r.run_id)
                    THEN 'MAINTAINED' ELSE 'RETIRED' END
                FROM projection_rebuild_runs r WHERE r.run_id=?
                """,String.class,runId);
    }

    /** Count at most limit+1 visible rows per dataset before expensive comparisons, under the fence. */
    public void requireComparisonBudget(int maximum,UUID... runs) {
        if (maximum<1 || maximum>1000000) throw new IllegalArgumentException("Invalid comparison budget.");
        if (overLimit("SELECT 1 FROM payments",maximum)
                || overLimit("SELECT 1 FROM payment_event_projections WHERE consumer_name=?",maximum,BASELINE))
            throw new com.fincore.payments.exception.ProjectionComparisonBudgetException();
        for (UUID run:java.util.Arrays.stream(runs).filter(java.util.Objects::nonNull).distinct().toList()) {
            if (overLimit("SELECT 1 FROM payment_event_projections WHERE consumer_name=?",maximum,"shadow-" + run)
                    || overLimit("""
                            SELECT 1 FROM projection_rebuild_items i JOIN projection_rebuild_runs r USING(run_id)
                            WHERE r.run_id=? AND i.generation=r.current_generation
                            """,maximum,run)) throw new com.fincore.payments.exception.ProjectionComparisonBudgetException();
        }
    }

    private boolean overLimit(String query,int maximum,Object... parameters) {
        Object[] values=java.util.Arrays.copyOf(parameters,parameters.length+1);
        values[parameters.length]=maximum+1;
        return jdbc.queryForObject("SELECT count(*) FROM (" + query + " LIMIT ?) sampled",Long.class,values)>maximum;
    }

    public Optional<Audit> request(UUID requestId) {
        return jdbc.query("SELECT * FROM projection_route_audit WHERE request_id = ?", (r,n) -> new Audit(
                r.getObject("request_id",UUID.class),r.getString("action"),r.getLong("expected_revision"),
                r.getObject("run_id",UUID.class),r.getObject("previous_run_id",UUID.class),r.getLong("build_generation"),r.getString("actor"),
                r.getString("reason"),r.getLong("revision"),r.getString("active_consumer"),
                r.getTimestamp("occurred_at").toInstant()),requestId).stream().findFirst();
    }

    public void switchRoute(UUID requestId,String action,long expected,UUID runId,long generation,
            String actor,String reason,String active) {
        var state=view();
        if (action.equals("REPLACE") || action.equals("PROMOTE") && state.shadowRunId()==null) {
            try {
                jdbc.queryForObject("SELECT run_id FROM projection_rebuild_runs WHERE run_id=? FOR UPDATE NOWAIT",UUID.class,runId);
            } catch (DataAccessException failure) { throw translateLock(failure); }
            jdbc.update("UPDATE projection_rebuild_runs SET routing_enrolled=true WHERE run_id=?",runId);
        }
        jdbc.update("""
                INSERT INTO projection_route_audit(request_id,action,expected_revision,run_id,build_generation,
                    actor,reason,revision,active_consumer,previous_run_id) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,requestId,action,expected,runId,generation,actor,reason,expected + 1,active,state.shadowRunId());
        UUID maintained=action.equals("RETIRE") ? null : runId;
        if (expected==0) {
            jdbc.update("""
                    INSERT INTO projection_routes(route_key,revision,shadow_run_id,active_consumer,last_request_id)
                    VALUES (true,?,?,?,?)
                    """,1,maintained,active,requestId);
        } else {
            int changed=jdbc.update("UPDATE projection_routes SET revision=?,shadow_run_id=?,active_consumer=?,last_request_id=? WHERE route_key AND revision=?",
                    expected + 1,maintained,active,requestId,expected);
            if (changed!=1) throw new com.fincore.payments.exception.ProjectionRoutingConflictException();
        }
    }

    /** Includes source-less external live records; ignoring these could make existing reads disappear. */
    public boolean equivalent(String shadow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH baseline AS (
                    SELECT x.payment_id,x.last_event_id,x.payment_version,x.status,x.merchant_reference,x.amount,
                        x.currency,x.occurred_at,r.payload::jsonb
                    FROM payment_event_projections x JOIN consumed_payment_events r
                        ON r.consumer_name=x.consumer_name AND r.event_id=x.last_event_id
                    WHERE x.consumer_name = 'payment-projection-v1'),
                candidate AS (
                    SELECT x.payment_id,x.last_event_id,x.payment_version,x.status,x.merchant_reference,x.amount,
                        x.currency,x.occurred_at,r.payload::jsonb
                    FROM payment_event_projections x JOIN consumed_payment_events r
                        ON r.consumer_name=x.consumer_name AND r.event_id=x.last_event_id WHERE x.consumer_name = ?)
                SELECT NOT EXISTS ((SELECT * FROM baseline EXCEPT SELECT * FROM candidate)
                    UNION ALL (SELECT * FROM candidate EXCEPT SELECT * FROM baseline))
                """,Boolean.class,shadow));
    }

    /** Both the replaceable view and its durable canonical receipt must still agree with current source. */
    public boolean currentSourceMatches(String consumer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM payments p
                    LEFT JOIN payment_outbox e ON e.payment_id=p.id AND e.payment_version=p.version
                    LEFT JOIN payment_event_projections x ON x.consumer_name=? AND x.payment_id=p.id
                    LEFT JOIN consumed_payment_events r ON r.consumer_name=x.consumer_name AND r.event_id=x.last_event_id
                    WHERE (e.event_id IS NOT NULL AND x.last_event_id=e.event_id AND x.payment_version=p.version
                        AND x.status=p.status AND x.merchant_reference=p.merchant_reference
                        AND x.amount=p.amount AND x.currency=p.currency AND x.occurred_at=e.occurred_at
                        AND e.payload::jsonb=r.payload::jsonb
                        AND e.payload::jsonb->'data'->>'status'=p.status
                        AND e.payload::jsonb->'data'->>'merchantReference'=p.merchant_reference
                        AND e.payload::jsonb->'data'->'amount'=to_jsonb(p.amount)
                        AND e.payload::jsonb->'data'->>'currency'=p.currency
                        AND e.occurred_at BETWEEN p.updated_at-INTERVAL '1 microsecond'
                            AND p.updated_at+INTERVAL '1 microsecond') IS NOT TRUE)
                """,Boolean.class,consumer));
    }

    private static RuntimeException translateLock(DataAccessException failure) {
        // PostgreSQL NOWAIT is 55P03. Spring's generic SQLState translator may leave it
        // UncategorizedSQLException; use the actual database contract, not a wrapper class.
        for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return new com.fincore.payments.exception.ProjectionRoutingConflictException();
            }
        }
        return failure;
    }

    public record Audit(UUID requestId,String action,long expectedRevision,UUID runId,UUID previousRunId,long buildGeneration,
            String actor,String reason,long revision,String activeConsumer,java.time.Instant occurredAt) {
        public ProjectionRoutingResult result(boolean replayed) {
            return new ProjectionRoutingResult(requestId,action,revision,activeConsumer,actor,reason,occurredAt,replayed);
        }
    }
}
