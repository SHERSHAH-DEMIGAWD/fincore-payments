-- Lifetime attempts remain monotonic, including across operator requeues. Each recovery starts
-- a fresh bounded retry cycle without changing event identity, payload, or ordering metadata.
ALTER TABLE payment_outbox ADD COLUMN cycle_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_outbox ADD COLUMN retry_generation BIGINT NOT NULL DEFAULT 0;
UPDATE payment_outbox SET cycle_attempts = attempts;

ALTER TABLE payment_outbox ADD CONSTRAINT payment_outbox_cycle_attempts_valid
    CHECK (cycle_attempts >= 0 AND cycle_attempts <= attempts);
ALTER TABLE payment_outbox ADD CONSTRAINT payment_outbox_retry_generation_nonnegative
    CHECK (retry_generation >= 0);

CREATE INDEX idx_payment_outbox_operations
    ON payment_outbox (status, occurred_at, event_id);

CREATE TABLE outbox_requeue_audit (
    request_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES payment_outbox (event_id),
    expected_generation BIGINT NOT NULL,
    resulting_generation BIGINT NOT NULL,
    previous_attempts INTEGER NOT NULL,
    previous_cycle_attempts INTEGER NOT NULL,
    operator VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    requeued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT outbox_requeue_audit_generation_unique UNIQUE (event_id, resulting_generation),
    CONSTRAINT outbox_requeue_audit_generation_valid CHECK (
        expected_generation >= 0 AND resulting_generation > expected_generation
        AND resulting_generation - expected_generation = 1
    ),
    CONSTRAINT outbox_requeue_audit_attempts_valid CHECK (
        previous_attempts > 0 AND previous_cycle_attempts >= 0
        AND previous_cycle_attempts <= previous_attempts
    ),
    CONSTRAINT outbox_requeue_audit_operator_valid CHECK (
        length(btrim(operator)) > 0 AND operator !~ '[[:cntrl:]]'
        AND position(U&'\2028' IN operator) = 0 AND position(U&'\2029' IN operator) = 0
    ),
    CONSTRAINT outbox_requeue_audit_reason_valid CHECK (
        length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]'
        AND position(U&'\2028' IN reason) = 0 AND position(U&'\2029' IN reason) = 0
    )
);

CREATE FUNCTION reject_outbox_requeue_audit_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Outbox requeue audit history is immutable.' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER outbox_requeue_audit_immutable
BEFORE UPDATE OR DELETE ON outbox_requeue_audit
FOR EACH ROW EXECUTE FUNCTION reject_outbox_requeue_audit_mutation();

CREATE FUNCTION protect_outbox_retry_metadata() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempts < OLD.attempts OR NEW.retry_generation < OLD.retry_generation THEN
        RAISE EXCEPTION 'Outbox lifetime attempts and retry generation cannot decrease.'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.retry_generation <> OLD.retry_generation THEN
        IF NEW.retry_generation - OLD.retry_generation <> 1
           OR OLD.status <> 'DEAD' OR NEW.status <> 'PENDING'
           OR NEW.attempts <> OLD.attempts OR NEW.cycle_attempts <> 0 THEN
            RAISE EXCEPTION 'A retry generation must advance by one when a dead event is requeued.'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.cycle_attempts < OLD.cycle_attempts THEN
        RAISE EXCEPTION 'Cycle attempts can reset only during an audited requeue.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_outbox_retry_metadata_valid
BEFORE UPDATE ON payment_outbox
FOR EACH ROW EXECUTE FUNCTION protect_outbox_retry_metadata();

CREATE FUNCTION require_outbox_requeue_audit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.retry_generation <> OLD.retry_generation AND NOT EXISTS (
        SELECT 1 FROM outbox_requeue_audit audit
        WHERE audit.event_id = NEW.event_id
          AND audit.expected_generation = OLD.retry_generation
          AND audit.resulting_generation = NEW.retry_generation
          AND audit.previous_attempts = OLD.attempts
          AND audit.previous_cycle_attempts = OLD.cycle_attempts
          AND audit.requeued_at = NEW.next_attempt_at
    ) THEN
        RAISE EXCEPTION 'Each outbox requeue must have a matching immutable audit record.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Deferred checking lets the service reset delivery metadata and insert its audit in either
-- order while requiring both to commit. The original envelope remains protected by V4.
CREATE CONSTRAINT TRIGGER payment_outbox_requeue_audited
AFTER UPDATE ON payment_outbox DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_outbox_requeue_audit();
