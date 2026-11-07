-- The consumer owns its history independently of the source payments/outbox. New subscriptions
-- start empty; no synthetic events or projections are backfilled from the current payment state.
CREATE TABLE consumed_payment_events (
    consumer_name VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    payment_version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT consumed_payment_events_version_unique UNIQUE (consumer_name, payment_id, payment_version),
    CONSTRAINT consumed_payment_events_consumer_name CHECK (length(btrim(consumer_name)) > 0),
    CONSTRAINT consumed_payment_events_version_nonnegative CHECK (payment_version >= 0),
    CONSTRAINT consumed_payment_events_schema_version CHECK (schema_version = 1),
    CONSTRAINT consumed_payment_events_event_type CHECK (event_type IN (
        'PAYMENT_CREATED', 'PAYMENT_AUTHORIZED', 'PAYMENT_CAPTURED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED'
    )),
    -- IS TRUE also rejects missing envelope fields, which otherwise produce SQL NULL.
    CONSTRAINT consumed_payment_events_payload_metadata CHECK ((
        jsonb_typeof(payload::jsonb) = 'object'
        AND payload::jsonb ->> 'eventId' = event_id::text
        AND payload::jsonb ->> 'paymentId' = payment_id::text
        AND payload::jsonb -> 'paymentVersion' = to_jsonb(payment_version)
        AND payload::jsonb -> 'schemaVersion' = to_jsonb(schema_version)
        AND payload::jsonb ->> 'eventType' = event_type
        AND jsonb_typeof(payload::jsonb -> 'occurredAt') = 'string'
        AND (payload::jsonb ->> 'occurredAt')::TIMESTAMP WITH TIME ZONE IS NOT NULL
        AND jsonb_typeof(payload::jsonb -> 'data') = 'object'
        AND payload::jsonb -> 'data' ->> 'id' = payment_id::text
        AND payload::jsonb -> 'data' ->> 'status' = substring(event_type FROM 9)
        AND jsonb_typeof(payload::jsonb -> 'data' -> 'merchantReference') = 'string'
        AND length(btrim(payload::jsonb -> 'data' ->> 'merchantReference')) BETWEEN 1 AND 255
        AND length(payload::jsonb -> 'data' ->> 'merchantReference') <= 255
        AND jsonb_typeof(payload::jsonb -> 'data' -> 'amount') = 'number'
        AND (payload::jsonb -> 'data' ->> 'amount')::NUMERIC > 0
        AND (payload::jsonb -> 'data' ->> 'amount')::NUMERIC <= 99999999999999999.99
        AND (payload::jsonb -> 'data' ->> 'amount')::NUMERIC =
            round((payload::jsonb -> 'data' ->> 'amount')::NUMERIC, 2)
        AND payload::jsonb -> 'data' ->> 'currency' ~ '^[A-Z]{3}$'
        AND jsonb_typeof(payload::jsonb -> 'data' -> 'createdAt') = 'string'
        AND (payload::jsonb -> 'data' ->> 'createdAt')::TIMESTAMP WITH TIME ZONE IS NOT NULL
        AND jsonb_typeof(payload::jsonb -> 'data' -> 'updatedAt') = 'string'
        AND (payload::jsonb -> 'data' ->> 'updatedAt')::TIMESTAMP WITH TIME ZONE IS NOT NULL
    ) IS TRUE)
);

CREATE FUNCTION reject_consumed_payment_event_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Consumed payment event history is immutable.' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER consumed_payment_events_immutable
BEFORE UPDATE OR DELETE ON consumed_payment_events
FOR EACH ROW EXECUTE FUNCTION reject_consumed_payment_event_mutation();

-- This is a replaceable read model, not a second financial ledger. The receipt FK requires the
-- event to be claimed before a projection can be created, within the same database transaction.
CREATE TABLE payment_event_projections (
    consumer_name VARCHAR(64) NOT NULL,
    payment_id UUID NOT NULL,
    last_event_id UUID NOT NULL,
    payment_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    merchant_reference VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, payment_id),
    CONSTRAINT payment_event_projections_receipt FOREIGN KEY (consumer_name, last_event_id)
        REFERENCES consumed_payment_events (consumer_name, event_id),
    CONSTRAINT payment_event_projections_version_nonnegative CHECK (payment_version >= 0),
    CONSTRAINT payment_event_projections_status CHECK (status IN (
        'CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUNDED'
    )),
    CONSTRAINT payment_event_projections_reference CHECK (length(btrim(merchant_reference)) > 0),
    CONSTRAINT payment_event_projections_amount_positive CHECK (amount > 0),
    CONSTRAINT payment_event_projections_currency CHECK (currency ~ '^[A-Z]{3}$')
);
