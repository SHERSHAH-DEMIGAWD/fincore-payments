-- Frozen latest-state input generations. This does not change the live consumer or its reads.
CREATE TABLE projection_rebuild_runs (
    run_id UUID PRIMARY KEY,
    consumer_name VARCHAR(64) NOT NULL UNIQUE,
    source_name VARCHAR(128) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    operator VARCHAR(100) NOT NULL CHECK (length(btrim(operator)) > 0 AND operator !~ '[[:cntrl:]]' AND operator !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    current_generation BIGINT NOT NULL DEFAULT 0 CHECK (current_generation >= 0),
    CHECK (consumer_name = 'shadow-' || run_id::text),
    CHECK (source_name ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]*$'),
    CHECK (length(btrim(topic)) > 0)
);

CREATE TABLE projection_rebuild_generations (
    run_id UUID NOT NULL REFERENCES projection_rebuild_runs(run_id),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    request_id UUID NOT NULL UNIQUE,
    expected_generation BIGINT,
    operator VARCHAR(100) NOT NULL CHECK (length(btrim(operator)) > 0 AND operator !~ '[[:cntrl:]]' AND operator !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    planned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    state VARCHAR(16) NOT NULL DEFAULT 'PLANNING'
        CHECK (state IN ('PLANNING','PLANNED','BUILDING','BUILT','BLOCKED')),
    total_items BIGINT NOT NULL DEFAULT 0 CHECK (total_items >= 0),
    blocked_items BIGINT NOT NULL DEFAULT 0 CHECK (blocked_items >= 0 AND blocked_items <= total_items),
    history_incomplete_items BIGINT NOT NULL DEFAULT 0 CHECK (history_incomplete_items >= 0 AND history_incomplete_items <= total_items),
    gap_configured_quarantines BIGINT NOT NULL CHECK (gap_configured_quarantines >= 0),
    gap_configured_failures BIGINT NOT NULL CHECK (gap_configured_failures >= 0),
    gap_other_quarantines BIGINT NOT NULL CHECK (gap_other_quarantines >= 0),
    gap_other_failures BIGINT NOT NULL CHECK (gap_other_failures >= 0),
    PRIMARY KEY (run_id, generation),
    CHECK ((generation = 0 AND expected_generation IS NULL AND request_id = run_id)
        OR (generation > 0 AND expected_generation IS NOT NULL
            AND generation::numeric = expected_generation::numeric + 1))
);

ALTER TABLE projection_rebuild_runs ADD CONSTRAINT projection_rebuild_current_generation_fk
    FOREIGN KEY (run_id, current_generation) REFERENCES projection_rebuild_generations(run_id,generation)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE projection_rebuild_items (
    run_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    payment_id UUID NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version >= 0),
    source_snapshot JSONB NOT NULL CHECK (jsonb_typeof(source_snapshot) = 'object'),
    event_id UUID,
    original_payload TEXT,
    canonical_payload TEXT,
    source_matches BOOLEAN NOT NULL,
    history_contiguous BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNVALIDATED'
        CHECK (status IN ('UNVALIDATED','PENDING','BLOCKED','APPLIED')),
    block_code VARCHAR(40),
    applied_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (run_id,generation,payment_id),
    FOREIGN KEY (run_id,generation) REFERENCES projection_rebuild_generations(run_id,generation),
    CHECK ((event_id IS NULL AND original_payload IS NULL) OR (event_id IS NOT NULL AND original_payload IS NOT NULL)),
    CHECK (original_payload IS NULL OR (
        jsonb_typeof(original_payload::jsonb) = 'object'
        AND original_payload::jsonb ->> 'eventId' = event_id::text
        AND original_payload::jsonb ->> 'paymentId' = payment_id::text
        AND original_payload::jsonb -> 'paymentVersion' = to_jsonb(source_version)) IS TRUE),
    CHECK (canonical_payload IS NULL OR (
        jsonb_typeof(canonical_payload::jsonb) = 'object'
        AND canonical_payload::jsonb ->> 'eventId' = event_id::text
        AND canonical_payload::jsonb ->> 'paymentId' = payment_id::text
        AND canonical_payload::jsonb -> 'paymentVersion' = to_jsonb(source_version)) IS TRUE),
    CHECK ((status = 'UNVALIDATED' AND canonical_payload IS NULL AND block_code IS NULL AND applied_at IS NULL)
        OR (status = 'PENDING' AND canonical_payload IS NOT NULL AND block_code IS NULL AND applied_at IS NULL)
        OR (status = 'BLOCKED' AND canonical_payload IS NULL AND applied_at IS NULL
            AND block_code IN ('NO_CURRENT_SOURCE_EVENT','INVALID_SOURCE_EVENT','SOURCE_SNAPSHOT_MISMATCH'))
        OR (status = 'APPLIED' AND canonical_payload IS NOT NULL AND block_code IS NULL AND applied_at IS NOT NULL))
);

CREATE INDEX projection_rebuild_pending ON projection_rebuild_items(run_id,generation,payment_id)
    WHERE status = 'PENDING';

CREATE FUNCTION protect_projection_rebuild_run() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Shadow rebuild identity is immutable.' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.run_id,NEW.consumer_name,NEW.source_name,NEW.topic,NEW.operator,NEW.reason,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.run_id,OLD.consumer_name,OLD.source_name,OLD.topic,OLD.operator,OLD.reason,OLD.created_at)
       OR NEW.current_generation::numeric <> OLD.current_generation::numeric + 1 THEN
        RAISE EXCEPTION 'Shadow rebuild identity is immutable and generation must advance once.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER projection_rebuild_runs_immutable BEFORE UPDATE OR DELETE ON projection_rebuild_runs
FOR EACH ROW EXECUTE FUNCTION protect_projection_rebuild_run();

CREATE FUNCTION protect_projection_rebuild_generation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    owner projection_rebuild_runs%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Shadow rebuild generations cannot be deleted.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO owner FROM projection_rebuild_runs WHERE run_id = NEW.run_id FOR UPDATE;
        IF NOT FOUND OR (NEW.generation = 0 AND owner.current_generation <> 0)
           OR (NEW.generation > 0 AND NEW.expected_generation IS DISTINCT FROM owner.current_generation)
           OR (NEW.generation > 0 AND EXISTS (SELECT 1 FROM projection_rebuild_items
               WHERE run_id = NEW.run_id AND generation = owner.current_generation AND status IN ('UNVALIDATED','PENDING'))) THEN
            RAISE EXCEPTION 'Shadow catch-up requires the current completed generation.' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.run_id,NEW.generation,NEW.request_id,NEW.expected_generation,NEW.operator,NEW.reason,NEW.planned_at,
           NEW.gap_configured_quarantines,NEW.gap_configured_failures,NEW.gap_other_quarantines,NEW.gap_other_failures)
       IS DISTINCT FROM ROW(OLD.run_id,OLD.generation,OLD.request_id,OLD.expected_generation,OLD.operator,OLD.reason,OLD.planned_at,
           OLD.gap_configured_quarantines,OLD.gap_configured_failures,OLD.gap_other_quarantines,OLD.gap_other_failures)
       OR (OLD.state <> 'PLANNING' AND ROW(NEW.total_items,NEW.blocked_items,NEW.history_incomplete_items)
           IS DISTINCT FROM ROW(OLD.total_items,OLD.blocked_items,OLD.history_incomplete_items))
       OR (OLD.state IN ('BUILT','BLOCKED'))
       OR (OLD.state <> 'PLANNING' AND NEW.state NOT IN ('BUILDING','BUILT','BLOCKED'))
       OR (OLD.state = 'PLANNING' AND NEW.state NOT IN ('PLANNED','BUILT','BLOCKED')) THEN
        RAISE EXCEPTION 'Shadow generation identity and frozen coverage are immutable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER projection_rebuild_generations_immutable BEFORE INSERT OR UPDATE OR DELETE ON projection_rebuild_generations
FOR EACH ROW EXECUTE FUNCTION protect_projection_rebuild_generation();

CREATE FUNCTION protect_projection_rebuild_item() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    generation_state VARCHAR(16);
    current_generation BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Shadow manifest evidence cannot be deleted.' USING ERRCODE = '23514';
    END IF;
    SELECT g.state,r.current_generation INTO generation_state,current_generation
    FROM projection_rebuild_generations g JOIN projection_rebuild_runs r USING (run_id)
    WHERE g.run_id = NEW.run_id AND g.generation = NEW.generation;
    IF TG_OP = 'INSERT' THEN
        IF generation_state IS DISTINCT FROM 'PLANNING' OR NEW.status <> 'UNVALIDATED' THEN
            RAISE EXCEPTION 'Shadow manifest inputs can only be inserted during planning.' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.run_id,NEW.generation,NEW.payment_id,NEW.source_version,NEW.source_snapshot,NEW.event_id,
           NEW.original_payload,NEW.source_matches,NEW.history_contiguous)
       IS DISTINCT FROM ROW(OLD.run_id,OLD.generation,OLD.payment_id,OLD.source_version,OLD.source_snapshot,OLD.event_id,
           OLD.original_payload,OLD.source_matches,OLD.history_contiguous) THEN
        RAISE EXCEPTION 'Shadow manifest original identity and evidence are immutable.' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'UNVALIDATED' AND generation_state = 'PLANNING' AND NEW.status IN ('PENDING','BLOCKED') THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'APPLIED' AND NEW.generation = current_generation
       AND generation_state IN ('PLANNED','BUILDING')
       AND ROW(NEW.canonical_payload,NEW.block_code) IS NOT DISTINCT FROM ROW(OLD.canonical_payload,OLD.block_code) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Shadow item progress may only advance once after validation.' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER projection_rebuild_items_immutable BEFORE INSERT OR UPDATE OR DELETE ON projection_rebuild_items
FOR EACH ROW EXECUTE FUNCTION protect_projection_rebuild_item();

CREATE FUNCTION check_projection_rebuild_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_run UUID := NEW.run_id;
    target_generation BIGINT;
    planned projection_rebuild_generations%ROWTYPE;
    actual_total BIGINT;
    actual_pending BIGINT;
    actual_blocked BIGINT;
    actual_incomplete BIGINT;
BEGIN
    -- Manifest insertion/classification is checked once through generation finalization. Applied
    -- checkpoints check only their own receipt/projection, avoiding an O(N²) planning commit.
    IF TG_TABLE_NAME = 'projection_rebuild_items' THEN
        IF NEW.status <> 'APPLIED' THEN RETURN NULL; END IF;
        IF NOT EXISTS (
            SELECT 1 FROM projection_rebuild_runs r
            JOIN consumed_payment_events c ON c.consumer_name = r.consumer_name AND c.event_id = NEW.event_id
            JOIN payment_event_projections v ON v.consumer_name = r.consumer_name AND v.payment_id = NEW.payment_id
            WHERE r.run_id = NEW.run_id AND c.payment_id = NEW.payment_id AND c.payment_version = NEW.source_version
                AND c.payload::jsonb = NEW.canonical_payload::jsonb
                AND v.last_event_id = NEW.event_id AND v.payment_version = NEW.source_version
                AND v.status = NEW.canonical_payload::jsonb -> 'data' ->> 'status'
                AND v.merchant_reference = NEW.canonical_payload::jsonb -> 'data' ->> 'merchantReference'
                AND to_jsonb(v.amount) = NEW.canonical_payload::jsonb -> 'data' -> 'amount'
                AND v.currency = NEW.canonical_payload::jsonb -> 'data' ->> 'currency'
                AND v.occurred_at = (NEW.canonical_payload::jsonb ->> 'occurredAt')::timestamptz
        ) THEN
            RAISE EXCEPTION 'Applied shadow progress requires its matching receipt and projection.' USING ERRCODE = '23514';
        END IF;
        -- A writer must finalize the generation when applying its last pending item. This
        -- indexed existence check also covers writers which omit the header update entirely.
        IF EXISTS (
            SELECT 1 FROM projection_rebuild_generations g
            WHERE g.run_id = NEW.run_id AND g.generation = NEW.generation
              AND g.state IN ('PLANNED','BUILDING')
              AND NOT EXISTS (SELECT 1 FROM projection_rebuild_items i
                  WHERE i.run_id = NEW.run_id AND i.generation = NEW.generation AND i.status = 'PENDING')
        ) THEN
            RAISE EXCEPTION 'Shadow generation state must finalize with its last applied item.' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;
    IF TG_TABLE_NAME = 'projection_rebuild_runs' THEN target_generation := NEW.current_generation;
    ELSE target_generation := NEW.generation; END IF;
    SELECT * INTO planned FROM projection_rebuild_generations
    WHERE run_id = target_run AND generation = target_generation;
    IF NOT FOUND OR planned.state = 'PLANNING' THEN
        RAISE EXCEPTION 'Shadow generation planning must finish atomically.' USING ERRCODE = '23514';
    END IF;
    IF planned.generation > (SELECT current_generation FROM projection_rebuild_runs WHERE run_id = target_run)
       OR EXISTS (SELECT 1 FROM projection_rebuild_items WHERE run_id = target_run AND generation = target_generation
            AND status = 'UNVALIDATED') THEN
        RAISE EXCEPTION 'Shadow generation must be current and all frozen inputs validated.' USING ERRCODE = '23514';
    END IF;
    SELECT count(*),count(*) FILTER (WHERE status IN ('UNVALIDATED','PENDING')),
        count(*) FILTER (WHERE status = 'BLOCKED'),count(*) FILTER (WHERE NOT history_contiguous)
    INTO actual_total,actual_pending,actual_blocked,actual_incomplete
    FROM projection_rebuild_items WHERE run_id = target_run AND generation = target_generation;
    IF planned.total_items <> actual_total OR planned.blocked_items <> actual_blocked
       OR planned.history_incomplete_items <> actual_incomplete
       OR (planned.state IN ('BUILT','BLOCKED') AND actual_pending <> 0)
       OR (planned.state = 'BUILT' AND actual_blocked <> 0)
       OR (planned.state = 'BLOCKED' AND actual_blocked = 0)
       OR (planned.state IN ('PLANNED','BUILDING') AND actual_pending = 0) THEN
        RAISE EXCEPTION 'Shadow generation state and durable item progress must agree.' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER projection_rebuild_run_consistent AFTER INSERT OR UPDATE ON projection_rebuild_runs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_rebuild_consistency();
CREATE CONSTRAINT TRIGGER projection_rebuild_generation_consistent AFTER INSERT OR UPDATE ON projection_rebuild_generations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_rebuild_consistency();
CREATE CONSTRAINT TRIGGER projection_rebuild_item_consistent AFTER INSERT OR UPDATE ON projection_rebuild_items
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_rebuild_consistency();
