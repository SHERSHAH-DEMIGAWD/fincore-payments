package com.fincore.payments.repository;

import com.fincore.payments.dto.ReconciliationGapContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only comparison of co-located source tables and logical-consumer artifacts. */
@Repository
public class ProjectionReconciliationRepository {
    private final JdbcTemplate jdbc;

    public ProjectionReconciliationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String CANDIDATES = """
            WITH parameters AS (
                SELECT CAST(? AS uuid) AS payment_filter, CAST(? AS uuid) AS after_id,
                    CAST(? AS integer) AS page_limit, CAST(? AS varchar) AS consumer
            ), candidate_ids AS (
                SELECT id AS payment_id FROM payments
                UNION SELECT payment_id FROM payment_event_projections, parameters WHERE consumer_name = consumer
                UNION SELECT payment_id FROM consumed_payment_events, parameters WHERE consumer_name = consumer
            ), scoped AS (
                SELECT payment_id FROM candidate_ids, parameters
                WHERE payment_filter IS NULL OR payment_id = payment_filter
            )
            """;

    public Snapshot snapshot(String consumer, UUID paymentId) {
        return jdbc.queryForObject(CANDIDATES + """
                SELECT statement_timestamp() AS observed_at, count(*) AS total FROM scoped
                """, (row, n) -> new Snapshot(row.getTimestamp("observed_at").toInstant(), row.getLong("total")),
                paymentId, null, 1, consumer);
    }

    public List<Comparison> page(String consumer, UUID paymentId, UUID after, int limit) {
        return jdbc.query(CANDIDATES + """
                , page_ids AS (
                    SELECT payment_id FROM scoped, parameters WHERE after_id IS NULL OR payment_id > after_id
                    ORDER BY payment_id LIMIT (SELECT page_limit + 1 FROM parameters)
                )
                SELECT ids.payment_id, p.version AS source_version, v.payment_version AS projection_version,
                    o.event_count, o.first_version, o.last_version, o.pending, o.dead, o.matched, o.missing, o.conflicting,
                    c.latest_version AS latest_receipt_version, c.unmatched,
                    (o.first_version = 0 AND o.last_version = p.version
                      AND o.event_count::numeric = p.version::numeric + 1) IS TRUE AS contiguous,
                    (o.event_count > 0 AND o.event_count::numeric <
                      o.last_version::numeric - o.first_version::numeric + 1) AS internal_gaps,
                    current_event.event_id AS current_event_id, current_event.payload AS current_payload,
                    (current_event.event_id IS NOT NULL
                     AND current_event.payload::jsonb -> 'data' ->> 'status' = p.status
                     AND current_event.payload::jsonb -> 'data' ->> 'merchantReference' = p.merchant_reference
                     AND current_event.payload::jsonb -> 'data' -> 'amount' = to_jsonb(p.amount)
                     AND current_event.payload::jsonb -> 'data' ->> 'currency' = p.currency
                     -- Event.from truncates to microseconds; JDBC may round the source timestamp.
                     AND current_event.occurred_at BETWEEN p.updated_at - INTERVAL '1 microsecond'
                         AND p.updated_at + INTERVAL '1 microsecond') IS TRUE AS source_snapshot_matches,
                    (v.status = p.status AND v.merchant_reference = p.merchant_reference
                     AND v.amount = p.amount AND v.currency = p.currency
                     AND v.occurred_at = current_event.occurred_at
                     AND v.last_event_id = current_event.event_id) IS TRUE AS projection_matches,
                    (r.payment_id = v.payment_id AND r.payment_version = v.payment_version
                     AND r.payload::jsonb -> 'data' ->> 'status' = v.status
                     AND r.payload::jsonb -> 'data' ->> 'merchantReference' = v.merchant_reference
                     AND r.payload::jsonb -> 'data' -> 'amount' = to_jsonb(v.amount)
                     AND r.payload::jsonb -> 'data' ->> 'currency' = v.currency
                     AND (r.payload::jsonb ->> 'occurredAt')::timestamptz = v.occurred_at) IS TRUE AS projection_receipt_matches
                FROM page_ids ids CROSS JOIN parameters a
                LEFT JOIN payments p ON p.id = ids.payment_id
                LEFT JOIN payment_event_projections v ON v.consumer_name = a.consumer AND v.payment_id = ids.payment_id
                LEFT JOIN consumed_payment_events r ON r.consumer_name = a.consumer AND r.event_id = v.last_event_id
                LEFT JOIN payment_outbox current_event ON current_event.payment_id = p.id
                    AND current_event.payment_version = p.version
                CROSS JOIN LATERAL (
                    SELECT count(*) AS event_count, min(e.payment_version) AS first_version,
                        max(e.payment_version) AS last_version,
                        count(*) FILTER (WHERE e.status = 'PENDING') AS pending,
                        count(*) FILTER (WHERE e.status = 'DEAD') AS dead,
                        count(*) FILTER (WHERE EXISTS (
                            SELECT 1 FROM consumed_payment_events x WHERE x.consumer_name = a.consumer
                            AND x.event_id = e.event_id AND x.payment_id = e.payment_id
                            AND x.payment_version = e.payment_version AND x.payload::jsonb = e.payload::jsonb)) AS matched,
                        count(*) FILTER (WHERE NOT EXISTS (
                            SELECT 1 FROM consumed_payment_events x WHERE x.consumer_name = a.consumer
                            AND (x.event_id = e.event_id OR (x.payment_id = e.payment_id AND x.payment_version = e.payment_version)))) AS missing,
                        count(*) FILTER (WHERE EXISTS (
                            SELECT 1 FROM consumed_payment_events x WHERE x.consumer_name = a.consumer
                            AND (x.event_id = e.event_id OR (x.payment_id = e.payment_id AND x.payment_version = e.payment_version)))
                            AND NOT EXISTS (
                            SELECT 1 FROM consumed_payment_events x WHERE x.consumer_name = a.consumer
                            AND x.event_id = e.event_id AND x.payment_id = e.payment_id
                            AND x.payment_version = e.payment_version AND x.payload::jsonb = e.payload::jsonb)) AS conflicting
                    FROM payment_outbox e WHERE e.payment_id = ids.payment_id
                ) o
                CROSS JOIN LATERAL (
                    SELECT max(x.payment_version) AS latest_version,
                        count(*) FILTER (WHERE NOT EXISTS (
                            SELECT 1 FROM payment_outbox e WHERE e.event_id = x.event_id
                            AND e.payment_id = x.payment_id AND e.payment_version = x.payment_version
                            AND e.payload::jsonb = x.payload::jsonb)) AS unmatched
                    FROM consumed_payment_events x WHERE x.consumer_name = a.consumer AND x.payment_id = ids.payment_id
                ) c
                ORDER BY ids.payment_id
                """, (row, n) -> new Comparison(row.getObject("payment_id", UUID.class),
                row.getObject("source_version", Long.class), row.getObject("projection_version", Long.class),
                row.getLong("event_count"), row.getObject("first_version", Long.class), row.getObject("last_version", Long.class),
                row.getLong("matched"), row.getLong("missing"), row.getLong("conflicting"), row.getLong("unmatched"),
                row.getObject("latest_receipt_version", Long.class), row.getLong("pending"), row.getLong("dead"),
                row.getBoolean("contiguous"), row.getBoolean("internal_gaps"),
                row.getObject("current_event_id", UUID.class), row.getString("current_payload"),
                row.getBoolean("source_snapshot_matches"), row.getBoolean("projection_matches"),
                row.getBoolean("projection_receipt_matches")), paymentId, after, limit, consumer);
    }

    public ReconciliationGapContext gaps(String consumer, String source, String topic) {
        return jdbc.queryForObject("""
                WITH failures AS (
                    SELECT f.*, (source_name = ? AND topic = ?) AS configured,
                        q.request_id AS quarantine, r.request_id AS replay
                    FROM consumer_event_failures f
                    LEFT JOIN consumer_recovery_audit q ON q.failure_id = f.failure_id AND q.action = 'QUARANTINE'
                    LEFT JOIN consumer_recovery_audit r ON r.failure_id = f.failure_id AND r.action = 'REPLAY'
                    WHERE f.consumer_name = ?
                )
                SELECT count(*) FILTER (WHERE configured AND quarantine IS NOT NULL AND replay IS NULL) AS cq,
                    count(*) FILTER (WHERE configured AND quarantine IS NULL AND resolved_at IS NULL) AS cf,
                    count(*) FILTER (WHERE NOT configured AND quarantine IS NOT NULL AND replay IS NULL) AS oq,
                    count(*) FILTER (WHERE NOT configured AND quarantine IS NULL AND resolved_at IS NULL) AS of
                FROM failures
                """, (row, n) -> new ReconciliationGapContext(row.getLong("cq"), row.getLong("cf"),
                row.getLong("oq"), row.getLong("of")), source, topic, consumer);
    }

    public record Snapshot(Instant observedAt, long totalCandidates) { }

    /** Payload is internal comparison input and is never returned through the report DTO. */
    public record Comparison(UUID paymentId, Long sourceVersion, Long projectionVersion,
            long eventCount, Long firstVersion, Long lastVersion, long matched, long missing, long conflicting,
            long unmatched, Long latestReceiptVersion, long pending, long dead, boolean contiguous,
            boolean internalGaps, UUID currentEventId, String currentPayload, boolean sourceSnapshotMatches,
            boolean projectionMatches, boolean projectionReceiptMatches) { }
}
