CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(255) COLLATE "C" PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    payment_id UUID NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT idempotency_key_format CHECK (idempotency_key ~ '^[!-~]{1,255}$'),
    CONSTRAINT idempotency_fingerprint_format CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_response_json CHECK (jsonb_typeof(response_body::jsonb) = 'object'),
    -- The complete snapshot claims the key before the payment INSERT, in the SAME transaction.
    -- Deferring the FK until commit permits that order but forbids a committed orphaned claim.
    CONSTRAINT idempotency_payment_fk FOREIGN KEY (payment_id) REFERENCES payments (id)
        DEFERRABLE INITIALLY DEFERRED
);

-- Supports FK checks on payment deletion; keys have no expiry/deletion workflow in this milestone.
CREATE INDEX idx_idempotency_records_payment_id ON idempotency_records (payment_id);
