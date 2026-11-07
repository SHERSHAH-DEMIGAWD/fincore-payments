-- The source namespace identifies a cluster/topic generation. A recreated topic must use a new
-- namespace so reused partition/offset positions cannot overwrite the old failure evidence.
-- Every observed record gets a compact marker, including records which never fail. This lets a
-- late failure from an old partition owner observe a newer owner's committed success.
CREATE TABLE consumer_event_positions (
    source_name VARCHAR(128) NOT NULL,
    consumer_name VARCHAR(64) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    record_partition INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    wire_fingerprint VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (source_name, consumer_name, topic, record_partition, record_offset),
    CONSTRAINT consumer_event_positions_source CHECK (source_name ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]*$'),
    CONSTRAINT consumer_event_positions_consumer CHECK (length(btrim(consumer_name)) > 0),
    CONSTRAINT consumer_event_positions_topic CHECK (length(btrim(topic)) > 0),
    CONSTRAINT consumer_event_positions_partition CHECK (record_partition >= 0),
    CONSTRAINT consumer_event_positions_offset CHECK (record_offset >= 0),
    CONSTRAINT consumer_event_positions_fingerprint CHECK (wire_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT consumer_event_positions_times CHECK (processed_at IS NULL OR processed_at >= observed_at)
);

CREATE FUNCTION protect_consumer_event_position() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Consumer source positions cannot be deleted.' USING ERRCODE = '23514';
    END IF;
    IF NEW.source_name IS DISTINCT FROM OLD.source_name
       OR NEW.consumer_name IS DISTINCT FROM OLD.consumer_name
       OR NEW.topic IS DISTINCT FROM OLD.topic
       OR NEW.record_partition IS DISTINCT FROM OLD.record_partition
       OR NEW.record_offset IS DISTINCT FROM OLD.record_offset
       OR NEW.wire_fingerprint IS DISTINCT FROM OLD.wire_fingerprint
       OR NEW.observed_at IS DISTINCT FROM OLD.observed_at
       OR (OLD.processed_at IS NOT NULL AND NEW.processed_at IS DISTINCT FROM OLD.processed_at) THEN
        RAISE EXCEPTION 'Consumer source identity and successful processing markers are immutable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER consumer_event_positions_immutable
BEFORE UPDATE OR DELETE ON consumer_event_positions
FOR EACH ROW EXECUTE FUNCTION protect_consumer_event_position();

CREATE TABLE consumer_event_failures (
    failure_id UUID PRIMARY KEY,
    source_name VARCHAR(128) NOT NULL,
    consumer_name VARCHAR(64) NOT NULL,
    consumer_group TEXT NOT NULL,
    topic VARCHAR(249) NOT NULL,
    record_partition INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    record_key TEXT,
    record_value TEXT,
    error_code VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    first_failed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT consumer_event_failures_position UNIQUE
        (source_name, consumer_name, topic, record_partition, record_offset),
    CONSTRAINT consumer_event_failures_position_fk FOREIGN KEY
        (source_name, consumer_name, topic, record_partition, record_offset)
        REFERENCES consumer_event_positions (source_name, consumer_name, topic, record_partition, record_offset),
    CONSTRAINT consumer_event_failures_source CHECK (source_name ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]*$'),
    CONSTRAINT consumer_event_failures_consumer CHECK (length(btrim(consumer_name)) > 0),
    CONSTRAINT consumer_event_failures_group CHECK (length(btrim(consumer_group)) > 0),
    CONSTRAINT consumer_event_failures_topic CHECK (length(btrim(topic)) > 0),
    CONSTRAINT consumer_event_failures_partition CHECK (record_partition >= 0),
    CONSTRAINT consumer_event_failures_offset CHECK (record_offset >= 0),
    CONSTRAINT consumer_event_failures_error CHECK
        (error_code IN ('INVALID_EVENT', 'EVENT_CONFLICT', 'CONSUMER_FAILED')),
    CONSTRAINT consumer_event_failures_attempts CHECK (attempts > 0),
    CONSTRAINT consumer_event_failures_times CHECK
        (last_failed_at >= first_failed_at AND (resolved_at IS NULL OR resolved_at >= last_failed_at))
);

CREATE INDEX consumer_event_failures_open ON consumer_event_failures
    (source_name, consumer_name, last_failed_at DESC, failure_id) WHERE resolved_at IS NULL;

CREATE FUNCTION protect_consumer_event_failure_evidence() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Consumer failure evidence cannot be deleted.' USING ERRCODE = '23514';
    END IF;
    IF NEW.failure_id IS DISTINCT FROM OLD.failure_id
       OR NEW.source_name IS DISTINCT FROM OLD.source_name
       OR NEW.consumer_name IS DISTINCT FROM OLD.consumer_name
       OR NEW.consumer_group IS DISTINCT FROM OLD.consumer_group
       OR NEW.topic IS DISTINCT FROM OLD.topic
       OR NEW.record_partition IS DISTINCT FROM OLD.record_partition
       OR NEW.record_offset IS DISTINCT FROM OLD.record_offset
       OR NEW.record_key IS DISTINCT FROM OLD.record_key
       OR NEW.record_value IS DISTINCT FROM OLD.record_value
       OR NEW.first_failed_at IS DISTINCT FROM OLD.first_failed_at THEN
        RAISE EXCEPTION 'Consumer failure original evidence is immutable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER consumer_event_failures_evidence_immutable
BEFORE UPDATE OR DELETE ON consumer_event_failures
FOR EACH ROW EXECUTE FUNCTION protect_consumer_event_failure_evidence();
