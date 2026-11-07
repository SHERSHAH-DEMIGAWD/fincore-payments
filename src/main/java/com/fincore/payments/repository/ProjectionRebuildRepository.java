package com.fincore.payments.repository;

import com.fincore.payments.dto.ReconciliationGapContext;
import com.fincore.payments.dto.ShadowRebuildPlanResult;
import com.fincore.payments.dto.ShadowRebuildView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectionRebuildRepository {
    private final JdbcTemplate jdbc;

    public ProjectionRebuildRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SOURCE_SNAPSHOT = """
            jsonb_build_object('id',p.id,'version',p.version,'status',p.status,
                'merchantReference',p.merchant_reference,'amount',p.amount,'currency',p.currency,
                'createdAtEpoch',extract(epoch FROM p.created_at),'updatedAtEpoch',extract(epoch FROM p.updated_at))
            """;

    public boolean insertRun(UUID runId, String consumer, String source, String topic, String operator, String reason) {
        return jdbc.update("""
                INSERT INTO projection_rebuild_runs(run_id,consumer_name,source_name,topic,operator,reason)
                VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, runId, consumer, source, topic, operator, reason) == 1;
    }

    public Optional<Run> findRun(UUID runId, boolean lock) {
        return jdbc.query("SELECT * FROM projection_rebuild_runs WHERE run_id = ?" + (lock ? " FOR UPDATE" : ""),
                (row,n) -> new Run(row.getObject("run_id",UUID.class),row.getString("consumer_name"),
                        row.getString("source_name"),row.getString("topic"),row.getString("operator"),
                        row.getString("reason"),row.getTimestamp("created_at").toInstant(),row.getLong("current_generation")),
                runId).stream().findFirst();
    }

    public boolean identityPopulated(String consumer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM consumed_payment_events WHERE consumer_name = ?)
                    OR EXISTS (SELECT 1 FROM payment_event_projections WHERE consumer_name = ?)
                """,Boolean.class,consumer,consumer));
    }

    public Optional<Plan> findRequest(UUID requestId) {
        return jdbc.query("""
                SELECT g.*,r.consumer_name FROM projection_rebuild_generations g
                JOIN projection_rebuild_runs r USING(run_id) WHERE g.request_id = ?
                """,(row,n) -> new Plan(row.getObject("run_id",UUID.class),row.getLong("generation"),
                row.getObject("request_id",UUID.class),row.getObject("expected_generation",Long.class),
                row.getString("operator"),row.getString("reason"),row.getString("consumer_name"),
                row.getTimestamp("planned_at").toInstant()),requestId).stream().findFirst();
    }

    public boolean beginGeneration(UUID runId,long generation,UUID requestId,Long expected,String operator,String reason,
            ReconciliationGapContext gaps) {
        return jdbc.update("""
                INSERT INTO projection_rebuild_generations(run_id,generation,request_id,expected_generation,operator,reason,
                    gap_configured_quarantines,gap_configured_failures,gap_other_quarantines,gap_other_failures)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,runId,generation,requestId,expected,operator,reason,gaps.configuredOpenQuarantines(),
                gaps.configuredOtherOpenFailures(),gaps.otherScopeOpenQuarantines(),gaps.otherScopeOtherOpenFailures()) == 1;
    }

    public void advanceGeneration(UUID runId,long generation) {
        if (jdbc.update("UPDATE projection_rebuild_runs SET current_generation = ? WHERE run_id = ?",generation,runId) != 1) {
            throw new IllegalStateException("Shadow rebuild generation could not advance.");
        }
    }

    public void materialize(UUID runId,long generation) {
        jdbc.update("""
                INSERT INTO projection_rebuild_items(run_id,generation,payment_id,source_version,source_snapshot,
                    event_id,original_payload,source_matches,history_contiguous)
                SELECT ?,?,p.id,p.version,
                """ + SOURCE_SNAPSHOT + """
                    ,e.event_id,e.payload,
                    (e.event_id IS NOT NULL AND e.payload::jsonb -> 'data' ->> 'status' = p.status
                    AND e.payload::jsonb -> 'data' ->> 'merchantReference' = p.merchant_reference
                    AND e.payload::jsonb -> 'data' -> 'amount' = to_jsonb(p.amount)
                    AND e.payload::jsonb -> 'data' ->> 'currency' = p.currency
                    AND e.occurred_at BETWEEN p.updated_at - INTERVAL '1 microsecond'
                        AND p.updated_at + INTERVAL '1 microsecond') IS TRUE,
                    (h.first_version = 0 AND h.last_version = p.version
                        AND h.total::numeric = p.version::numeric + 1) IS TRUE
                FROM payments p
                LEFT JOIN payment_outbox e ON e.payment_id = p.id AND e.payment_version = p.version
                CROSS JOIN LATERAL (
                    SELECT min(payment_version) AS first_version,max(payment_version) AS last_version,count(*) AS total
                    FROM payment_outbox WHERE payment_id = p.id
                ) h
                """,runId,generation);
    }

    public List<Item> items(UUID runId,long generation,String status,int limit) {
        return jdbc.query("""
                SELECT * FROM projection_rebuild_items WHERE run_id = ? AND generation = ? AND status = ?
                ORDER BY payment_id LIMIT ?
                """,(row,n) -> new Item(row.getObject("payment_id",UUID.class),row.getLong("source_version"),
                row.getObject("event_id",UUID.class),row.getString("original_payload"),row.getString("canonical_payload"),
                row.getBoolean("source_matches")),runId,generation,status,limit);
    }

    public void classify(UUID runId,long generation,UUID paymentId,String canonical,String blockCode) {
        if (jdbc.update("""
                UPDATE projection_rebuild_items SET status = ?,canonical_payload = ?,block_code = ?
                WHERE run_id = ? AND generation = ? AND payment_id = ? AND status = 'UNVALIDATED'
                """,blockCode == null ? "PENDING" : "BLOCKED",canonical,blockCode,runId,generation,paymentId) != 1) {
            throw new IllegalStateException("Shadow manifest validation could not advance.");
        }
    }

    public void finishPlan(UUID runId,long generation) {
        jdbc.update("""
                UPDATE projection_rebuild_generations g SET total_items = c.total,blocked_items = c.blocked,
                    history_incomplete_items = c.incomplete,
                    state = CASE WHEN c.pending > 0 THEN 'PLANNED' WHEN c.blocked > 0 THEN 'BLOCKED' ELSE 'BUILT' END
                FROM (SELECT count(*) AS total,count(*) FILTER (WHERE status = 'BLOCKED') AS blocked,
                    count(*) FILTER (WHERE NOT history_contiguous) AS incomplete,
                    count(*) FILTER (WHERE status = 'PENDING') AS pending
                    FROM projection_rebuild_items WHERE run_id = ? AND generation = ?) c
                WHERE g.run_id = ? AND g.generation = ?
                """,runId,generation,runId,generation);
    }

    public boolean canonicalMatches(UUID runId,long generation,UUID paymentId,String canonical) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT canonical_payload::jsonb = CAST(? AS jsonb) FROM projection_rebuild_items
                WHERE run_id = ? AND generation = ? AND payment_id = ?
                """,Boolean.class,canonical,runId,generation,paymentId));
    }

    public void markApplied(UUID runId,long generation,UUID paymentId) {
        if (jdbc.update("""
                UPDATE projection_rebuild_items SET status = 'APPLIED',applied_at = clock_timestamp()
                WHERE run_id = ? AND generation = ? AND payment_id = ? AND status = 'PENDING'
                """,runId,generation,paymentId) != 1) {
            throw new IllegalStateException("Shadow checkpoint could not advance.");
        }
    }

    public boolean projectionMatches(UUID runId,long generation,UUID paymentId,String consumer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM projection_rebuild_items i
                    JOIN payment_event_projections v ON v.consumer_name = ? AND v.payment_id = i.payment_id
                    WHERE i.run_id = ? AND i.generation = ? AND i.payment_id = ?
                    AND v.last_event_id = i.event_id AND v.payment_version = i.source_version
                    AND v.status = i.canonical_payload::jsonb -> 'data' ->> 'status'
                    AND v.merchant_reference = i.canonical_payload::jsonb -> 'data' ->> 'merchantReference'
                    AND to_jsonb(v.amount) = i.canonical_payload::jsonb -> 'data' -> 'amount'
                    AND v.currency = i.canonical_payload::jsonb -> 'data' ->> 'currency'
                    AND v.occurred_at = (i.canonical_payload::jsonb ->> 'occurredAt')::timestamptz)
                """,Boolean.class,consumer,runId,generation,paymentId));
    }

    public void finishStep(UUID runId,long generation) {
        jdbc.update("""
                UPDATE projection_rebuild_generations SET state = CASE
                    WHEN EXISTS (SELECT 1 FROM projection_rebuild_items WHERE run_id = ? AND generation = ? AND status = 'PENDING')
                        THEN 'BUILDING'
                    WHEN blocked_items > 0 THEN 'BLOCKED' ELSE 'BUILT' END
                WHERE run_id = ? AND generation = ?
                """,runId,generation,runId,generation);
    }

    public ShadowRebuildView view(UUID runId,long generation) {
        return jdbc.queryForObject("""
                SELECT r.*,g.*,c.pending,c.applied,
                    (SELECT payment_id FROM projection_rebuild_items WHERE run_id = r.run_id
                        AND generation = g.generation AND status = 'PENDING' ORDER BY payment_id LIMIT 1) AS next_id
                FROM projection_rebuild_runs r JOIN projection_rebuild_generations g USING(run_id)
                CROSS JOIN LATERAL (SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                    count(*) FILTER (WHERE status = 'APPLIED') AS applied
                    FROM projection_rebuild_items WHERE run_id = r.run_id AND generation = g.generation) c
                WHERE r.run_id = ? AND g.generation = ?
                """,(row,n) -> new ShadowRebuildView(runId,row.getString("consumer_name"),row.getString("source_name"),
                row.getString("topic"),generation,row.getString("state"),row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("planned_at").toInstant(),row.getLong("total_items"),row.getLong("pending"),
                row.getLong("applied"),row.getLong("blocked_items"),row.getLong("history_incomplete_items"),
                row.getObject("next_id",UUID.class),new ReconciliationGapContext(row.getLong("gap_configured_quarantines"),
                row.getLong("gap_configured_failures"),row.getLong("gap_other_quarantines"),row.getLong("gap_other_failures"))),
                runId,generation);
    }

    public ValidationCounts validation(UUID runId,long generation,String consumer) {
        return jdbc.queryForObject("""
                WITH manifest AS (SELECT * FROM projection_rebuild_items WHERE run_id = ? AND generation = ?),
                compared AS (
                    SELECT i.*,p.id AS current_payment,
                        (i.source_snapshot =
                """ + SOURCE_SNAPSHOT + """
                        AND e.event_id IS NOT DISTINCT FROM i.event_id
                        AND e.payload::jsonb IS NOT DISTINCT FROM i.original_payload::jsonb) IS TRUE AS source_same,
                        c.event_id IS NOT NULL AS expected_receipt_present,v.payment_version AS observed_shadow_version,
                        (c.event_id = i.event_id AND c.payment_id = i.payment_id
                            AND c.payment_version = i.source_version AND c.payload::jsonb = i.canonical_payload::jsonb
                            AND v.payment_version = i.source_version AND v.last_event_id = i.event_id
                            AND v.status = i.canonical_payload::jsonb -> 'data' ->> 'status'
                            AND v.merchant_reference = i.canonical_payload::jsonb -> 'data' ->> 'merchantReference'
                            AND to_jsonb(v.amount) = i.canonical_payload::jsonb -> 'data' -> 'amount'
                            AND v.currency = i.canonical_payload::jsonb -> 'data' ->> 'currency'
                            AND v.occurred_at = (i.canonical_payload::jsonb ->> 'occurredAt')::timestamptz) IS TRUE AS shadow_same
                    FROM manifest i
                    LEFT JOIN payments p ON p.id = i.payment_id
                    LEFT JOIN payment_outbox e ON e.payment_id = p.id AND e.payment_version = p.version
                    LEFT JOIN consumed_payment_events c ON c.consumer_name = ? AND c.event_id = i.event_id
                    LEFT JOIN payment_event_projections v ON v.consumer_name = ? AND v.payment_id = i.payment_id
                )
                SELECT statement_timestamp() AS observed_at,
                    count(*) FILTER (WHERE NOT shadow_same AND (status = 'APPLIED'
                        OR (status = 'PENDING' AND (expected_receipt_present OR observed_shadow_version >= source_version)))) AS shadow_mismatches,
                    count(*) FILTER (WHERE current_payment IS NOT NULL AND NOT source_same) AS source_changed,
                    count(*) FILTER (WHERE current_payment IS NULL) AS source_removed,
                    (SELECT count(*) FROM payments p WHERE NOT EXISTS (SELECT 1 FROM manifest i WHERE i.payment_id = p.id)) AS source_added,
                    ((SELECT count(*) FROM payment_event_projections v WHERE consumer_name = ?
                        AND NOT EXISTS (SELECT 1 FROM manifest i WHERE i.payment_id = v.payment_id))
                    + (SELECT count(*) FROM consumed_payment_events x WHERE consumer_name = ?
                        AND NOT EXISTS (SELECT 1 FROM projection_rebuild_items i WHERE i.run_id = ?
                            AND i.event_id = x.event_id AND i.payment_id = x.payment_id
                            AND i.source_version = x.payment_version AND i.canonical_payload::jsonb = x.payload::jsonb))) AS unplanned
                FROM compared
                """,(row,n) -> new ValidationCounts(row.getTimestamp("observed_at").toInstant(),
                row.getLong("shadow_mismatches"),row.getLong("source_changed"),row.getLong("source_added"),
                row.getLong("source_removed"),row.getLong("unplanned")),runId,generation,consumer,consumer,consumer,consumer,runId);
    }

    public record Run(UUID runId,String consumerName,String sourceName,String topic,String operator,String reason,
            Instant createdAt,long generation) { }
    public record Plan(UUID runId,long generation,UUID requestId,Long expectedGeneration,String operator,String reason,
            String consumerName,Instant plannedAt) {
        public ShadowRebuildPlanResult result(boolean replayed) {
            return new ShadowRebuildPlanResult(runId,generation,requestId,consumerName,plannedAt,replayed);
        }
    }
    public record Item(UUID paymentId,long sourceVersion,UUID eventId,String originalPayload,String canonicalPayload,
            boolean sourceMatches) { }
    public record ValidationCounts(Instant observedAt,long shadowMismatches,long sourceChanged,long sourceAdded,
            long sourceRemoved,long unplannedShadowRows) { }
}
