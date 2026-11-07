-- Quarantine authorizes acknowledgement of one preserved source position, while retaining a gap.
CREATE TABLE consumer_recovery_audit (
    request_id UUID PRIMARY KEY,
    failure_id UUID NOT NULL REFERENCES consumer_event_failures (failure_id),
    action VARCHAR(16) NOT NULL CHECK (action IN ('QUARANTINE', 'REPLAY')),
    fingerprint VARCHAR(64) NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    operator VARCHAR(100) NOT NULL CHECK (length(btrim(operator)) > 0 AND operator !~ '[[:cntrl:]]' AND operator !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (failure_id, action)
);

CREATE FUNCTION protect_consumer_recovery_audit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    evidence consumer_event_failures%ROWTYPE;
    position consumer_event_positions%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Consumer recovery audit is immutable.' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO evidence FROM consumer_event_failures WHERE failure_id = NEW.failure_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Consumer failure is required.' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO position FROM consumer_event_positions
    WHERE source_name = evidence.source_name AND consumer_name = evidence.consumer_name
      AND topic = evidence.topic AND record_partition = evidence.record_partition
      AND record_offset = evidence.record_offset FOR UPDATE;
    IF NEW.fingerprint IS DISTINCT FROM position.wire_fingerprint THEN
        RAISE EXCEPTION 'Consumer recovery fingerprint conflicts.' USING ERRCODE = '23514';
    END IF;
    IF NEW.action = 'QUARANTINE' AND
       (position.processed_at IS NOT NULL OR evidence.resolved_at IS NOT NULL
        OR evidence.error_code NOT IN ('INVALID_EVENT', 'EVENT_CONFLICT')) THEN
        RAISE EXCEPTION 'Only an open permanent failure may be quarantined.' USING ERRCODE = '23514';
    END IF;
    IF NEW.action = 'REPLAY' AND NOT EXISTS
       (SELECT 1 FROM consumer_recovery_audit WHERE failure_id = NEW.failure_id AND action = 'QUARANTINE') THEN
        RAISE EXCEPTION 'Replay requires a quarantine.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER consumer_recovery_audit_immutable
BEFORE INSERT OR UPDATE OR DELETE ON consumer_recovery_audit
FOR EACH ROW EXECUTE FUNCTION protect_consumer_recovery_audit();

-- Deferred checks allow either write order but require success and replay audit in one commit.
CREATE FUNCTION check_consumer_recovery_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_failure UUID;
BEGIN
    IF TG_TABLE_NAME = 'consumer_event_positions' THEN
        SELECT failure_id INTO target_failure FROM consumer_event_failures
        WHERE source_name = NEW.source_name AND consumer_name = NEW.consumer_name
          AND topic = NEW.topic AND record_partition = NEW.record_partition AND record_offset = NEW.record_offset;
    ELSE
        target_failure := NEW.failure_id;
    END IF;
    IF target_failure IS NULL THEN RETURN NULL; END IF;
    IF EXISTS (
        SELECT 1 FROM consumer_recovery_audit q
        JOIN consumer_event_failures f ON f.failure_id = q.failure_id
        JOIN consumer_event_positions p USING (source_name, consumer_name, topic, record_partition, record_offset)
        LEFT JOIN consumer_recovery_audit r ON r.failure_id = q.failure_id AND r.action = 'REPLAY'
        WHERE q.failure_id = target_failure AND q.action = 'QUARANTINE' AND
          ((r.request_id IS NULL AND (p.processed_at IS NOT NULL OR f.resolved_at IS NOT NULL))
           OR (r.request_id IS NOT NULL AND (p.processed_at IS NULL OR f.resolved_at IS NULL)))
    ) THEN
        RAISE EXCEPTION 'Quarantine resolution requires atomic successful replay and audit.' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER consumer_recovery_audit_consistent
AFTER INSERT ON consumer_recovery_audit DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_consumer_recovery_consistency();
CREATE CONSTRAINT TRIGGER consumer_recovery_position_consistent
AFTER UPDATE ON consumer_event_positions DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_consumer_recovery_consistency();
CREATE CONSTRAINT TRIGGER consumer_recovery_failure_consistent
AFTER UPDATE ON consumer_event_failures DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_consumer_recovery_consistency();
