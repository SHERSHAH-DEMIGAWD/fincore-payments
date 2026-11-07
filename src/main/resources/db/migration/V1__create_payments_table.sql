CREATE TABLE payments (
    id UUID PRIMARY KEY,
    merchant_reference VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payments_merchant_reference_not_blank CHECK (merchant_reference ~ '[^[:space:]]'),
    CONSTRAINT payments_amount_positive CHECK (amount > 0),
    CONSTRAINT payments_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payments_status_valid CHECK (status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUNDED')),
    CONSTRAINT payments_version_nonnegative CHECK (version >= 0)
);

-- Merchant references are intentionally non-unique: creation is not idempotent yet.
-- Supports locating payments for a merchant's order during support/investigation.
CREATE INDEX idx_payments_merchant_reference ON payments (merchant_reference);
