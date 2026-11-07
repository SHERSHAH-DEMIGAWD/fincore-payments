package com.fincore.payments.repository;

import com.fincore.payments.dto.ConsumerGapReport;
import com.fincore.payments.dto.ConsumerQuarantineView;
import com.fincore.payments.dto.ConsumerRecoveryCommand;
import com.fincore.payments.dto.ConsumerRecoveryResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
public class ConsumerRecoveryRepository {
    private final JdbcTemplate jdbc;

    public ConsumerRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<FailureEvidence> findFailure(UUID failureId, String source, String consumer, String topic) {
        return jdbc.query("""
                SELECT f.*, p.wire_fingerprint, p.processed_at
                FROM consumer_event_failures f JOIN consumer_event_positions p
                  USING (source_name, consumer_name, topic, record_partition, record_offset)
                WHERE failure_id = ? AND source_name = ? AND consumer_name = ? AND topic = ?
                """, (row, n) -> new FailureEvidence(row.getObject("failure_id", UUID.class),
                new ConsumerRecord<>(row.getString("topic"), row.getInt("record_partition"),
                        row.getLong("record_offset"), row.getString("record_key"), row.getString("record_value")),
                row.getString("wire_fingerprint"), row.getString("error_code"),
                row.getTimestamp("resolved_at") != null, row.getTimestamp("processed_at") != null),
                failureId, source, consumer, topic).stream().findFirst();
    }

    public Optional<Audit> findRequest(UUID requestId) {
        return jdbc.query("SELECT * FROM consumer_recovery_audit WHERE request_id = ?", (row, n) ->
                new Audit(new ConsumerRecoveryCommand(row.getObject("request_id", UUID.class),
                        row.getObject("failure_id", UUID.class), row.getString("fingerprint"),
                        row.getString("operator"), row.getString("reason")), row.getString("action"),
                        row.getTimestamp("decided_at").toInstant()), requestId).stream().findFirst();
    }

    public boolean hasAction(UUID failureId, String action) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM consumer_recovery_audit WHERE failure_id = ? AND action = ?)
                """, Boolean.class, failureId, action));
    }

    public boolean isQuarantined(String source, String consumer, ConsumerRecord<String, String> record) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM consumer_event_failures f
                    JOIN consumer_recovery_audit a USING (failure_id)
                    WHERE source_name = ? AND consumer_name = ? AND topic = ?
                      AND record_partition = ? AND record_offset = ? AND a.action = 'QUARANTINE')
                """, Boolean.class, source, consumer, record.topic(), record.partition(), record.offset()));
    }

    public boolean insert(ConsumerRecoveryCommand command, String action) {
        return jdbc.update("""
                INSERT INTO consumer_recovery_audit (request_id, failure_id, action, fingerprint, operator, reason)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, command.requestId(), command.failureId(), action, command.expectedFingerprint(),
                command.operator(), command.reason()) == 1;
    }

    public List<ConsumerQuarantineView> quarantines(String source, String consumer, String topic,
            boolean openOnly, int limit) {
        return jdbc.query("""
                SELECT f.failure_id, source_name, consumer_name, topic, record_partition, record_offset,
                    q.fingerprint, q.request_id, q.operator, q.reason, q.decided_at,
                    r.request_id AS replay_request_id, r.decided_at AS replayed_at
                FROM consumer_recovery_audit q JOIN consumer_event_failures f USING (failure_id)
                LEFT JOIN consumer_recovery_audit r ON r.failure_id = q.failure_id AND r.action = 'REPLAY'
                WHERE q.action = 'QUARANTINE' AND source_name = ? AND consumer_name = ? AND topic = ?
                  AND (? = FALSE OR r.request_id IS NULL)
                ORDER BY q.decided_at DESC, f.failure_id LIMIT ?
                """, (row, n) -> new ConsumerQuarantineView(row.getObject("failure_id", UUID.class),
                row.getString("source_name"), row.getString("consumer_name"), row.getString("topic"),
                row.getInt("record_partition"), row.getLong("record_offset"), row.getString("fingerprint"),
                row.getObject("request_id", UUID.class), row.getString("operator"), row.getString("reason"),
                row.getTimestamp("decided_at").toInstant(), row.getObject("replay_request_id", UUID.class),
                instant(row, "replayed_at")), source, consumer, topic, openOnly, limit);
    }

    public ConsumerGapReport gaps(String source, String consumer, String topic) {
        return jdbc.queryForObject("""
                SELECT statement_timestamp() AS observed_at,
                    count(*) FILTER (WHERE q.request_id IS NOT NULL AND r.request_id IS NULL) AS quarantined,
                    count(*) FILTER (WHERE q.request_id IS NULL AND f.resolved_at IS NULL) AS other_open
                FROM consumer_event_failures f
                LEFT JOIN consumer_recovery_audit q ON q.failure_id = f.failure_id AND q.action = 'QUARANTINE'
                LEFT JOIN consumer_recovery_audit r ON r.failure_id = f.failure_id AND r.action = 'REPLAY'
                WHERE source_name = ? AND consumer_name = ? AND topic = ?
                """, (row, n) -> {
                    long quarantined = row.getLong("quarantined");
                    long open = row.getLong("other_open");
                    return new ConsumerGapReport(source, consumer, topic, row.getTimestamp("observed_at").toInstant(),
                            quarantined > 0 || open > 0 ? "DEGRADED" : "NO_KNOWN_GAPS", quarantined, open);
                }, source, consumer, topic);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record FailureEvidence(UUID failureId, ConsumerRecord<String, String> record, String fingerprint,
            String errorCode, boolean resolved, boolean processed) { }

    public record Audit(ConsumerRecoveryCommand command, String action, Instant decidedAt) {
        public ConsumerRecoveryResult result(boolean replayed) {
            return new ConsumerRecoveryResult(command.requestId(), command.failureId(), action, decidedAt, replayed);
        }
    }
}
