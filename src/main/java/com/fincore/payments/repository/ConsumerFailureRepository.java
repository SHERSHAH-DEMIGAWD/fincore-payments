package com.fincore.payments.repository;

import com.fincore.payments.domain.ConsumerFailureErrorCode;
import com.fincore.payments.dto.ConsumerFailureView;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Repository
public class ConsumerFailureRepository {
    private final JdbcTemplate jdbc;

    public ConsumerFailureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String sourceName, String consumerName, String consumerGroup,
            ConsumerRecord<String, String> record, ConsumerFailureErrorCode code) {
        boolean alreadyProcessed = lockPosition(sourceName, consumerName, record);
        int changed = jdbc.update("""
                WITH observed AS (SELECT clock_timestamp() AS now)
                INSERT INTO consumer_event_failures
                    (failure_id, source_name, consumer_name, consumer_group, topic, record_partition,
                     record_offset, record_key, record_value, error_code, first_failed_at, last_failed_at, resolved_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, observed.now, observed.now,
                    CASE WHEN ? THEN observed.now ELSE NULL END FROM observed WHERE TRUE
                ON CONFLICT (source_name, consumer_name, topic, record_partition, record_offset)
                DO UPDATE SET
                    error_code = EXCLUDED.error_code,
                    attempts = LEAST(consumer_event_failures.attempts::bigint + 1, 2147483647)::integer,
                    last_failed_at = GREATEST(consumer_event_failures.last_failed_at, EXCLUDED.last_failed_at),
                    resolved_at = CASE WHEN ? THEN GREATEST(consumer_event_failures.resolved_at,
                        consumer_event_failures.last_failed_at, EXCLUDED.last_failed_at) ELSE NULL END
                WHERE consumer_event_failures.record_key IS NOT DISTINCT FROM EXCLUDED.record_key
                  AND consumer_event_failures.record_value IS NOT DISTINCT FROM EXCLUDED.record_value
                """, UUID.randomUUID(), sourceName, consumerName, consumerGroup, record.topic(),
                record.partition(), record.offset(), record.key(), record.value(), code.name(),
                alreadyProcessed, alreadyProcessed);
        if (changed != 1) {
            throw new IllegalStateException("Kafka source position conflicts with existing failure evidence.");
        }
    }

    public void resolve(String sourceName, String consumerName, ConsumerRecord<String, String> record) {
        lockPosition(sourceName, consumerName, record);
        jdbc.update("""
                UPDATE consumer_event_positions SET processed_at = COALESCE(processed_at,
                    GREATEST(observed_at, clock_timestamp()))
                WHERE source_name = ? AND consumer_name = ? AND topic = ?
                  AND record_partition = ? AND record_offset = ?
                """, sourceName, consumerName, record.topic(), record.partition(), record.offset());
        int changed = jdbc.update("""
                UPDATE consumer_event_failures SET resolved_at =
                    COALESCE(resolved_at, GREATEST(last_failed_at, clock_timestamp()))
                WHERE source_name = ? AND consumer_name = ? AND topic = ?
                  AND record_partition = ? AND record_offset = ?
                  AND record_key IS NOT DISTINCT FROM ? AND record_value IS NOT DISTINCT FROM ?
                """, sourceName, consumerName, record.topic(), record.partition(), record.offset(),
                record.key(), record.value());
        if (changed == 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM consumer_event_failures
                    WHERE source_name = ? AND consumer_name = ? AND topic = ?
                      AND record_partition = ? AND record_offset = ?)
                """, Boolean.class, sourceName, consumerName, record.topic(), record.partition(), record.offset()))) {
            throw new IllegalStateException("Kafka source position conflicts with existing failure evidence.");
        }
    }

    // Call within a transaction. Processing, diagnostics and recovery share this lock order.
    public boolean lockPosition(String sourceName, String consumerName, ConsumerRecord<String, String> record) {
        String fingerprint = fingerprint(record.key(), record.value());
        jdbc.update("""
                INSERT INTO consumer_event_positions
                    (source_name, consumer_name, topic, record_partition, record_offset, wire_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, sourceName, consumerName, record.topic(), record.partition(), record.offset(), fingerprint);
        return jdbc.queryForObject("""
                SELECT wire_fingerprint, processed_at FROM consumer_event_positions
                WHERE source_name = ? AND consumer_name = ? AND topic = ?
                  AND record_partition = ? AND record_offset = ? FOR UPDATE
                """, (row, number) -> {
                    if (!fingerprint.equals(row.getString("wire_fingerprint"))) {
                        throw new IllegalStateException("Kafka source position conflicts with existing record identity.");
                    }
                    return row.getTimestamp("processed_at") != null;
                }, sourceName, consumerName, record.topic(), record.partition(), record.offset());
    }

    public static String fingerprint(String key, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprintPart(digest, key);
            fingerprintPart(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static void fingerprintPart(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }

    public List<ConsumerFailureView> list(String sourceName, String consumerName, boolean openOnly, int limit) {
        return jdbc.query("""
                SELECT failure_id, source_name, consumer_name, consumer_group, topic, record_partition,
                       record_offset, error_code, attempts, first_failed_at, last_failed_at, resolved_at,
                       wire_fingerprint
                FROM consumer_event_failures JOIN consumer_event_positions
                  USING (source_name, consumer_name, topic, record_partition, record_offset)
                WHERE source_name = ? AND consumer_name = ? AND (? = FALSE OR resolved_at IS NULL)
                ORDER BY last_failed_at DESC, failure_id LIMIT ?
                """, (row, number) -> {
                    Timestamp resolved = row.getTimestamp("resolved_at");
                    return new ConsumerFailureView(row.getObject("failure_id", UUID.class),
                            row.getString("source_name"), row.getString("consumer_name"),
                            row.getString("consumer_group"), row.getString("topic"),
                            row.getInt("record_partition"), row.getLong("record_offset"),
                            ConsumerFailureErrorCode.valueOf(row.getString("error_code")), row.getInt("attempts"),
                            row.getTimestamp("first_failed_at").toInstant(),
                            row.getTimestamp("last_failed_at").toInstant(), resolved == null ? null : resolved.toInstant(),
                            row.getString("wire_fingerprint"));
                }, sourceName, consumerName, openOnly, limit);
    }
}
