package com.fincore.payments.repository;

import com.fincore.payments.domain.PaymentEvent;
import com.fincore.payments.domain.PaymentStatus;
import com.fincore.payments.dto.PaymentProjectionResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentEventConsumerRepository {
    private final JdbcTemplate jdbc;

    public PaymentEventConsumerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireReadCommitted() {
        Boolean compatible=jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getTransactionIsolation()==java.sql.Connection.TRANSACTION_READ_COMMITTED);
        if (!Boolean.TRUE.equals(compatible)) {
            throw new IllegalStateException("Live event processing requires READ COMMITTED isolation.");
        }
    }

    public void lockRouting() {
        jdbc.execute("SELECT pg_advisory_xact_lock_shared(" + ProjectionRoutingRepository.FENCE + ")");
    }

    public Optional<String> maintainedShadow() {
        return jdbc.query("SELECT 'shadow-' || shadow_run_id::text FROM projection_routes WHERE shadow_run_id IS NOT NULL",
                (row,n) -> row.getString(1)).stream().findFirst();
    }

    public boolean isEverEnrolledRun(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT routing_enrolled FROM projection_rebuild_runs WHERE run_id = ?",Boolean.class,runId));
    }

    public String targetLifecycle(UUID runId) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN NOT r.routing_enrolled THEN 'ISOLATED'
                    WHEN EXISTS (SELECT 1 FROM projection_routes p WHERE p.shadow_run_id=r.run_id)
                    THEN 'MAINTAINED' ELSE 'RETIRED' END
                FROM projection_rebuild_runs r WHERE r.run_id=?
                """,String.class,runId);
    }

    public boolean maintainedPaymentMatches(String shadow,UUID paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payment_event_projections b JOIN payment_event_projections s USING(payment_id)
                    JOIN consumed_payment_events br ON br.consumer_name=b.consumer_name AND br.event_id=b.last_event_id
                    JOIN consumed_payment_events sr ON sr.consumer_name=s.consumer_name AND sr.event_id=s.last_event_id
                    WHERE b.consumer_name='payment-projection-v1' AND s.consumer_name=? AND b.payment_id=?
                        AND ROW(b.last_event_id,b.payment_version,b.status,b.merchant_reference,b.amount,b.currency,b.occurred_at,br.payload::jsonb)
                            = ROW(s.last_event_id,s.payment_version,s.status,s.merchant_reference,s.amount,s.currency,s.occurred_at,sr.payload::jsonb))
                """,Boolean.class,shadow,paymentId));
    }

    public boolean insertReceipt(String consumerName, PaymentEvent event, String payload) {
        // A conflicting concurrent INSERT waits for its owner to commit or roll back. No SQL
        // exception is swallowed, so the caller can safely issue a fresh READ COMMITTED query.
        return jdbc.update("""
                INSERT INTO consumed_payment_events
                    (consumer_name, event_id, payment_id, payment_version, event_type, schema_version, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, consumerName, event.eventId(), event.paymentId(), event.paymentVersion(),
                event.eventType().name(), event.schemaVersion(), payload) == 1;
    }

    public boolean matchesExistingReceipt(String consumerName, PaymentEvent event, String payload) {
        // Both unique identities must resolve to the same envelope. JSONB equality ignores
        // formatting, object-key order and insignificant numeric scale without changing storage.
        List<Boolean> matches = jdbc.query("""
                SELECT event_id = ? AND payment_id = ? AND payment_version = ?
                       AND payload::jsonb = CAST(? AS jsonb) AS identical
                FROM consumed_payment_events
                WHERE consumer_name = ?
                  AND (event_id = ? OR (payment_id = ? AND payment_version = ?))
                """, (row, number) -> row.getBoolean("identical"), event.eventId(), event.paymentId(),
                event.paymentVersion(), payload, consumerName, event.eventId(), event.paymentId(),
                event.paymentVersion());
        return matches.size() == 1 && matches.getFirst();
    }

    public void applyProjection(String consumerName, PaymentEvent event) {
        jdbc.update("""
                INSERT INTO payment_event_projections
                    (consumer_name, payment_id, last_event_id, payment_version, status,
                     merchant_reference, amount, currency, occurred_at, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (consumer_name, payment_id) DO UPDATE SET
                    last_event_id = EXCLUDED.last_event_id,
                    payment_version = EXCLUDED.payment_version,
                    status = EXCLUDED.status,
                    merchant_reference = EXCLUDED.merchant_reference,
                    amount = EXCLUDED.amount,
                    currency = EXCLUDED.currency,
                    occurred_at = EXCLUDED.occurred_at,
                    projected_at = EXCLUDED.projected_at
                WHERE EXCLUDED.payment_version > payment_event_projections.payment_version
                """, consumerName, event.paymentId(), event.eventId(), event.paymentVersion(),
                event.data().status().name(), event.data().merchantReference(), event.data().amount(),
                event.data().currency(), Timestamp.from(event.occurredAt()));
    }

    public Optional<PaymentProjectionResponse> findProjection(String consumerName, UUID paymentId) {
        return projection("SELECT * FROM payment_event_projections WHERE consumer_name = ? AND payment_id = ?",
                consumerName,paymentId);
    }

    public Optional<PaymentProjectionResponse> findRoutedProjection(UUID paymentId) {
        return projection("""
                SELECT * FROM payment_event_projections WHERE consumer_name = COALESCE(
                    (SELECT active_consumer FROM projection_routes),'payment-projection-v1') AND payment_id = ?
                """,paymentId);
    }

    private Optional<PaymentProjectionResponse> projection(String sql,Object... arguments) {
        return jdbc.query(sql, (row, number) -> new PaymentProjectionResponse(row.getObject("payment_id", UUID.class),
                row.getObject("last_event_id", UUID.class), row.getLong("payment_version"),
                PaymentStatus.valueOf(row.getString("status")), row.getString("merchant_reference"),
                row.getBigDecimal("amount"), row.getString("currency"),
                row.getTimestamp("occurred_at").toInstant(), row.getTimestamp("projected_at").toInstant()),
                arguments).stream().findFirst();
    }
}
