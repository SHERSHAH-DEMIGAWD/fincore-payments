package com.fincore.payments.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.payments.domain.OutboxStatus;
import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.dto.OutboxEventResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(PaymentEvent event) {
        jdbc.update("""
                INSERT INTO payment_outbox
                    (event_id, payment_id, payment_version, event_type, schema_version, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.paymentId(), event.paymentVersion(), event.eventType().name(),
                event.schemaVersion(), Timestamp.from(event.occurredAt()), serialize(event));
    }

    public Optional<Candidate> lockNextDue() {
        // The NOT EXISTS predicate blocks later versions even while an earlier row is locked,
        // backed off, or DEAD. SKIP LOCKED lets other workers process different payments.
        return jdbc.query("""
                SELECT o.event_id, o.payload, o.attempts FROM payment_outbox o
                WHERE o.status = 'PENDING' AND o.next_attempt_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                    SELECT 1 FROM payment_outbox earlier
                    WHERE earlier.payment_id = o.payment_id AND earlier.payment_version < o.payment_version
                      AND earlier.status <> 'DELIVERED'
                  )
                ORDER BY o.occurred_at, o.event_id
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, (row, number) -> new Candidate(row.getObject("event_id", UUID.class),
                row.getString("payload"), row.getInt("attempts"))).stream().findFirst();
    }

    public void markDelivered(UUID eventId) {
        int changed = jdbc.update("""
                UPDATE payment_outbox SET status = 'DELIVERED', attempts = attempts + 1,
                    cycle_attempts = cycle_attempts + 1,
                    delivered_at = CURRENT_TIMESTAMP, last_error_code = NULL
                WHERE event_id = ? AND status = 'PENDING'
                """, eventId);
        if (changed != 1) throw new IllegalStateException("Locked outbox event could not be acknowledged.");
    }

    public boolean recordFailure(UUID eventId, int expectedAttempts, int maxAttempts, long delayMs) {
        // This runs AFTER the failed delivery transaction has rolled back. A concurrent worker
        // may already have succeeded or scheduled a retry; the expected attempt guards against
        // overwriting either outcome with this stale failure report.
        return jdbc.update("""
                UPDATE payment_outbox SET attempts = attempts + 1, cycle_attempts = cycle_attempts + 1,
                    status = CASE WHEN cycle_attempts + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_error_code = 'PUBLISH_FAILED'
                WHERE event_id = ? AND status = 'PENDING' AND attempts = ?
                """, maxAttempts, delayMs, eventId, expectedAttempts) == 1;
    }

    public Optional<Integer> failureCycleAttempts(UUID eventId, int expectedAttempts) {
        // The failure recorder owns a fresh transaction. Lock while reading the cycle budget so
        // the delay and conditional failure update describe the same pending lifetime attempt.
        return jdbc.query("""
                SELECT cycle_attempts FROM payment_outbox
                WHERE event_id = ? AND status = 'PENDING' AND attempts = ? FOR UPDATE
                """, (row, number) -> row.getInt("cycle_attempts"), eventId, expectedAttempts)
                .stream().findFirst();
    }

    public void deliverLocally(PaymentEvent event) {
        // Preserve the exact saved envelope rather than reserializing it. The receiver deduplicates
        // by event ID. Local receipt and acknowledgment share the worker's transaction.
        jdbc.update("""
                INSERT INTO local_payment_event_inbox (event_id, payload)
                VALUES (?, (SELECT payload FROM payment_outbox WHERE event_id = ?))
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.eventId());
    }

    public List<OutboxEventResponse> findByPaymentId(UUID paymentId) {
        return jdbc.query("""
                SELECT * FROM payment_outbox WHERE payment_id = ? ORDER BY payment_version
                """, (row, number) -> {
                    Timestamp deliveredAt = row.getTimestamp("delivered_at");
                    return new OutboxEventResponse(deserialize(row.getString("payload")),
                            OutboxStatus.valueOf(row.getString("status")), row.getInt("attempts"),
                            row.getTimestamp("next_attempt_at").toInstant(),
                            deliveredAt == null ? null : deliveredAt.toInstant(), row.getString("last_error_code"));
                }, paymentId);
    }

    public PaymentEvent deserialize(String payload) {
        try {
            return mapper.readValue(payload, PaymentEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read a stored payment event.", exception);
        }
    }

    private String serialize(PaymentEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize a payment event.", exception);
        }
    }

    public record Candidate(UUID eventId, String payload, int attempts) { }
}
