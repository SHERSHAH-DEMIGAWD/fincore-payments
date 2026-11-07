package com.fincore.payments.repository;

import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.domain.PaymentEventType;
import com.fincore.payments.dto.OperationalOutboxEvent;
import com.fincore.payments.dto.OutboxOverview;
import com.fincore.payments.dto.RequeueAuditView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventOperationsRepository {
    private final JdbcTemplate jdbc;

    public EventOperationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EventState> lockEvent(UUID eventId) {
        return jdbc.query("""
                SELECT status, attempts, cycle_attempts, retry_generation
                FROM payment_outbox WHERE event_id = ? FOR UPDATE
                """, (row, number) -> new EventState(OutboxStatus.valueOf(row.getString("status")),
                row.getInt("attempts"), row.getInt("cycle_attempts"), row.getLong("retry_generation")),
                eventId).stream().findFirst();
    }

    public Optional<RequeueAuditView> findRequest(UUID requestId) {
        return jdbc.query("SELECT * FROM outbox_requeue_audit WHERE request_id = ?",
                EventOperationsRepository::audit, requestId).stream().findFirst();
    }

    public Instant requeue(UUID eventId, long expectedGeneration) {
        List<Instant> changed = jdbc.query("""
                UPDATE payment_outbox SET status = 'PENDING', cycle_attempts = 0,
                    retry_generation = retry_generation + 1, next_attempt_at = clock_timestamp(),
                    last_error_code = NULL
                WHERE event_id = ? AND status = 'DEAD' AND retry_generation = ?
                RETURNING next_attempt_at
                """, (row, number) -> row.getTimestamp("next_attempt_at").toInstant(),
                eventId, expectedGeneration);
        if (changed.size() != 1) throw new IllegalStateException("Locked event could not be requeued.");
        return changed.getFirst();
    }

    public boolean insertAudit(RequeueAuditView audit) {
        return jdbc.update("""
                INSERT INTO outbox_requeue_audit
                    (request_id, event_id, expected_generation, resulting_generation,
                     previous_attempts, previous_cycle_attempts, operator, reason, requeued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, audit.requestId(), audit.eventId(), audit.expectedGeneration(),
                audit.resultingGeneration(), audit.previousAttempts(), audit.previousCycleAttempts(),
                audit.operator(), audit.reason(), Timestamp.from(audit.requeuedAt())) == 1;
    }

    public OutboxOverview overview() {
        // All buckets come from one SQL statement and one MVCC snapshot. A row held by a
        // concurrent dispatcher can still count as ready; this describes state, not spare capacity.
        return jdbc.queryForObject("""
                WITH observed AS (
                    SELECT o.status, o.occurred_at, o.next_attempt_at,
                        EXISTS (
                            SELECT 1 FROM payment_outbox earlier
                            WHERE earlier.payment_id = o.payment_id
                              AND earlier.payment_version < o.payment_version
                              AND earlier.status <> 'DELIVERED'
                        ) AS blocked
                    FROM payment_outbox o
                )
                SELECT CURRENT_TIMESTAMP AS observed_at,
                    count(*) FILTER (WHERE status = 'PENDING') AS pending,
                    count(*) FILTER (WHERE status = 'DELIVERED') AS delivered,
                    count(*) FILTER (WHERE status = 'DEAD') AS dead,
                    count(*) FILTER (WHERE status = 'PENDING' AND NOT blocked
                        AND next_attempt_at <= CURRENT_TIMESTAMP) AS ready,
                    count(*) FILTER (WHERE status = 'PENDING' AND blocked) AS blocked,
                    count(*) FILTER (WHERE status = 'PENDING' AND NOT blocked
                        AND next_attempt_at > CURRENT_TIMESTAMP) AS backed_off,
                    min(occurred_at) FILTER (WHERE status = 'PENDING') AS oldest_pending_at,
                    min(occurred_at) FILTER (WHERE status = 'PENDING' AND NOT blocked
                        AND next_attempt_at <= CURRENT_TIMESTAMP) AS oldest_ready_at
                FROM observed
                """, (row, number) -> new OutboxOverview(instant(row, "observed_at"),
                row.getLong("pending"), row.getLong("delivered"), row.getLong("dead"),
                row.getLong("ready"), row.getLong("blocked"), row.getLong("backed_off"),
                instant(row, "oldest_pending_at"), instant(row, "oldest_ready_at")));
    }

    public List<OperationalOutboxEvent> events(OutboxStatus status, int limit, String consumerName) {
        String filter = status == null ? "" : "WHERE o.status = ?";
        String query = """
                SELECT o.event_id, o.payment_id, o.payment_version, o.event_type, o.status,
                    o.attempts, o.cycle_attempts, o.retry_generation, o.occurred_at,
                    o.next_attempt_at, o.delivered_at, o.last_error_code,
                    blocker.event_id AS oldest_blocking_event_id,
                    projection.payment_version AS latest_projection_version
                FROM payment_outbox o
                LEFT JOIN LATERAL (
                    SELECT earlier.event_id FROM payment_outbox earlier
                    WHERE earlier.payment_id = o.payment_id
                      AND earlier.payment_version < o.payment_version
                      AND earlier.status <> 'DELIVERED'
                    ORDER BY earlier.payment_version LIMIT 1
                ) blocker ON TRUE
                LEFT JOIN payment_event_projections projection
                    ON projection.payment_id = o.payment_id AND projection.consumer_name = ?
                """ + filter + " ORDER BY o.occurred_at, o.event_id LIMIT ?";
        Object[] parameters = status == null
                ? new Object[]{consumerName, limit} : new Object[]{consumerName, status.name(), limit};
        return jdbc.query(query, (row, number) -> new OperationalOutboxEvent(
                row.getObject("event_id", UUID.class), row.getObject("payment_id", UUID.class),
                row.getLong("payment_version"), PaymentEventType.valueOf(row.getString("event_type")),
                OutboxStatus.valueOf(row.getString("status")), row.getInt("attempts"),
                row.getInt("cycle_attempts"), row.getLong("retry_generation"),
                instant(row, "occurred_at"), instant(row, "next_attempt_at"), instant(row, "delivered_at"),
                row.getString("last_error_code"), row.getObject("oldest_blocking_event_id", UUID.class),
                row.getObject("latest_projection_version", Long.class)), parameters);
    }

    public boolean eventExists(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM payment_outbox WHERE event_id = ?)", Boolean.class, eventId));
    }

    public List<RequeueAuditView> requeues(UUID eventId, int limit) {
        return jdbc.query("""
                SELECT * FROM outbox_requeue_audit WHERE event_id = ?
                ORDER BY resulting_generation DESC LIMIT ?
                """, EventOperationsRepository::audit, eventId, limit);
    }

    private static RequeueAuditView audit(ResultSet row, int number) throws SQLException {
        return new RequeueAuditView(row.getObject("request_id", UUID.class),
                row.getObject("event_id", UUID.class), row.getLong("expected_generation"),
                row.getLong("resulting_generation"), row.getInt("previous_attempts"),
                row.getInt("previous_cycle_attempts"), row.getString("operator"), row.getString("reason"),
                instant(row, "requeued_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record EventState(OutboxStatus status, int attempts, int cycleAttempts, long retryGeneration) { }
}
