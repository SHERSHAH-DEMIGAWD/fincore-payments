-- Start recording events for new application operations only. Historical payment states do not
-- establish historical event times, so this migration deliberately emits no synthetic events.
CREATE TABLE payment_outbox (
    event_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    payment_version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    CONSTRAINT payment_outbox_payment_version_unique UNIQUE (payment_id, payment_version),
    CONSTRAINT payment_outbox_version_nonnegative CHECK (payment_version >= 0),
    CONSTRAINT payment_outbox_schema_version CHECK (schema_version = 1),
    CONSTRAINT payment_outbox_event_type CHECK (event_type IN (
        'PAYMENT_CREATED', 'PAYMENT_AUTHORIZED', 'PAYMENT_CAPTURED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED'
    )),
    CONSTRAINT payment_outbox_attempts_nonnegative CHECK (attempts >= 0),
    CONSTRAINT payment_outbox_delivery_state CHECK (
        (status = 'PENDING' AND delivered_at IS NULL) OR
        (status = 'DELIVERED' AND delivered_at IS NOT NULL AND attempts > 0) OR
        (status = 'DEAD' AND delivered_at IS NULL AND attempts > 0)
    ),
    -- IS TRUE rejects absent JSON fields as well as false comparisons. A CHECK without it
    -- would accept SQL NULL for a missing field. The timestamp comparison uses PostgreSQL's
    -- timestamp precision; the envelope itself remains stored without rewriting its bytes.
    CONSTRAINT payment_outbox_payload_metadata CHECK ((
        jsonb_typeof(payload::jsonb) = 'object'
        AND payload::jsonb ->> 'eventId' = event_id::text
        AND payload::jsonb ->> 'paymentId' = payment_id::text
        AND payload::jsonb -> 'paymentVersion' = to_jsonb(payment_version)
        AND payload::jsonb -> 'schemaVersion' = to_jsonb(schema_version)
        AND payload::jsonb ->> 'eventType' = event_type
        AND (payload::jsonb ->> 'occurredAt')::TIMESTAMP WITH TIME ZONE = occurred_at
        AND jsonb_typeof(payload::jsonb -> 'data') = 'object'
        AND payload::jsonb -> 'data' ->> 'id' = payment_id::text
        AND payload::jsonb -> 'data' ->> 'status' = substring(event_type FROM 9)
    ) IS TRUE)
);

-- The relay searches ready rows, while the second partial index finds any earlier event
-- still blocking delivery for the same payment (including a DEAD event).
CREATE INDEX idx_payment_outbox_due
    ON payment_outbox (next_attempt_at, occurred_at, event_id) WHERE status = 'PENDING';
CREATE INDEX idx_payment_outbox_undelivered
    ON payment_outbox (payment_id, payment_version) WHERE status <> 'DELIVERED';

CREATE FUNCTION protect_payment_outbox_envelope() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment event history cannot be deleted.' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.event_id, NEW.payment_id, NEW.payment_version, NEW.event_type,
           NEW.schema_version, NEW.occurred_at, NEW.payload)
       IS DISTINCT FROM
       ROW(OLD.event_id, OLD.payment_id, OLD.payment_version, OLD.event_type,
           OLD.schema_version, OLD.occurred_at, OLD.payload) THEN
        RAISE EXCEPTION 'Payment event envelopes are immutable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_outbox_envelope_immutable
BEFORE UPDATE OR DELETE ON payment_outbox
FOR EACH ROW EXECUTE FUNCTION protect_payment_outbox_envelope();

-- A durable local receiver makes this milestone executable without claiming broker delivery.
-- Its event-ID primary key is the consumer-side duplicate-delivery boundary.
CREATE TABLE local_payment_event_inbox (
    event_id UUID PRIMARY KEY REFERENCES payment_outbox (event_id),
    payload TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE FUNCTION validate_local_payment_event_inbox() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM payment_outbox event
        WHERE event.event_id = NEW.event_id AND event.payload = NEW.payload
    ) THEN
        RAISE EXCEPTION 'A received payment event must match its original outbox envelope.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER local_payment_event_inbox_valid
BEFORE INSERT ON local_payment_event_inbox
FOR EACH ROW EXECUTE FUNCTION validate_local_payment_event_inbox();

CREATE FUNCTION reject_local_payment_event_inbox_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Received payment event history is immutable.' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER local_payment_event_inbox_immutable
BEFORE UPDATE OR DELETE ON local_payment_event_inbox
FOR EACH ROW EXECUTE FUNCTION reject_local_payment_event_inbox_mutation();
