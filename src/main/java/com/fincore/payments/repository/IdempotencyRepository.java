package com.fincore.payments.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called inside PaymentService's transaction, using the same datasource as JPA. */
    public boolean claim(String key, String fingerprint, UUID paymentId, String responseBody, Instant createdAt) {
        return claim(com.fincore.payments.domain.MerchantIdentity.LEGACY_ID,key,fingerprint,paymentId,responseBody,createdAt);
    }

    public boolean claim(UUID merchantId,String key, String fingerprint, UUID paymentId, String responseBody, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO idempotency_records
                    (merchant_id,idempotency_key, request_fingerprint, payment_id, response_body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (merchant_id,idempotency_key) DO NOTHING
                """, merchantId,key, fingerprint, paymentId, responseBody, Timestamp.from(createdAt)) == 1;
    }

    public StoredResult find(String key) {
        return find(com.fincore.payments.domain.MerchantIdentity.LEGACY_ID,key);
    }

    public StoredResult find(UUID merchantId,String key) {
        return jdbc.queryForObject("""
                SELECT request_fingerprint, response_body FROM idempotency_records WHERE merchant_id=? AND idempotency_key = ?
                """, (row, number) -> new StoredResult(row.getString("request_fingerprint"),
                row.getString("response_body")), merchantId,key);
    }

    public record StoredResult(String fingerprint, String responseBody) { }
}
