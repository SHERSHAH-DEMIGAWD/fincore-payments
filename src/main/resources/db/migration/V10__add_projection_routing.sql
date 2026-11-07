-- One lifetime shadow may be enrolled in live delivery. The baseline remains warm, so
-- audited route changes can toggle between two maintained read models without fanout growth.
ALTER TABLE projection_rebuild_runs ADD COLUMN routing_enrolled BOOLEAN NOT NULL DEFAULT FALSE;

-- Enrollment changes the locked run row. A REPEATABLE READ catch-up using an older
-- snapshot therefore receives 40001 when it locks the run after enrollment commits.
CREATE OR REPLACE FUNCTION protect_projection_rebuild_run() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Shadow rebuild identity is immutable.' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.run_id,NEW.consumer_name,NEW.source_name,NEW.topic,NEW.operator,NEW.reason,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.run_id,OLD.consumer_name,OLD.source_name,OLD.topic,OLD.operator,OLD.reason,OLD.created_at)
       OR OLD.routing_enrolled
       OR (NEW.routing_enrolled AND NEW.current_generation <> OLD.current_generation)
       OR (NOT NEW.routing_enrolled AND NEW.current_generation::numeric <> OLD.current_generation::numeric + 1) THEN
        RAISE EXCEPTION 'Shadow generation may advance until its one-time routing enrollment.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE projection_route_audit (
    request_id UUID PRIMARY KEY,
    action VARCHAR(16) NOT NULL CHECK (action IN ('PROMOTE','ROLLBACK')),
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
    run_id UUID NOT NULL REFERENCES projection_rebuild_runs(run_id),
    build_generation BIGINT NOT NULL CHECK (build_generation >= 0),
    actor VARCHAR(100) NOT NULL CHECK (length(btrim(actor)) > 0 AND actor !~ '[[:cntrl:]]' AND actor !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    revision BIGINT NOT NULL UNIQUE CHECK (revision > 0),
    active_consumer VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id,build_generation) REFERENCES projection_rebuild_generations(run_id,generation),
    CHECK (revision::numeric = expected_revision::numeric + 1),
    CHECK ((action = 'PROMOTE' AND active_consumer = 'shadow-' || run_id::text)
        OR (action = 'ROLLBACK' AND active_consumer = 'payment-projection-v1'))
);

CREATE TABLE projection_routes (
    route_key BOOLEAN PRIMARY KEY CHECK (route_key),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    shadow_run_id UUID NOT NULL UNIQUE REFERENCES projection_rebuild_runs(run_id),
    active_consumer VARCHAR(64) NOT NULL,
    last_request_id UUID NOT NULL UNIQUE REFERENCES projection_route_audit(request_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (active_consumer IN ('payment-projection-v1','shadow-' || shadow_run_id::text))
);

CREATE FUNCTION protect_projection_route_audit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Projection route decisions are immutable.' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER projection_route_audit_immutable BEFORE UPDATE OR DELETE ON projection_route_audit
FOR EACH ROW EXECUTE FUNCTION protect_projection_route_audit();

CREATE FUNCTION protect_projection_route() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Projection enrollment cannot be removed.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.revision <> 1 OR NEW.active_consumer <> 'shadow-' || NEW.shadow_run_id::text THEN
            RAISE EXCEPTION 'Projection enrollment starts with the first promotion.' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.route_key IS DISTINCT FROM OLD.route_key
       OR NEW.shadow_run_id IS DISTINCT FROM OLD.shadow_run_id
       OR NEW.revision::numeric <> OLD.revision::numeric + 1
       OR NEW.active_consumer IS NOT DISTINCT FROM OLD.active_consumer
       OR NEW.last_request_id IS NOT DISTINCT FROM OLD.last_request_id THEN
        RAISE EXCEPTION 'Projection routing must advance once and retain its enrolled shadow.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER projection_routes_protected BEFORE INSERT OR UPDATE OR DELETE ON projection_routes
FOR EACH ROW EXECUTE FUNCTION protect_projection_route();

CREATE FUNCTION check_projection_route_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    route projection_routes%ROWTYPE;
    decision projection_route_audit%ROWTYPE;
    previous projection_route_audit%ROWTYPE;
    enrolled projection_route_audit%ROWTYPE;
BEGIN
    SELECT * INTO route FROM projection_routes WHERE route_key;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Projection route decisions require their committed route.' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO decision FROM projection_route_audit WHERE request_id = route.last_request_id;
    IF NOT FOUND OR decision.revision <> route.revision OR decision.run_id <> route.shadow_run_id
       OR decision.active_consumer <> route.active_consumer THEN
        RAISE EXCEPTION 'Projection route and its current audit must agree.' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO enrolled FROM projection_route_audit WHERE revision = 1;
    IF NOT FOUND OR enrolled.action <> 'PROMOTE' OR enrolled.expected_revision <> 0
       OR enrolled.run_id <> route.shadow_run_id
       OR NOT EXISTS (SELECT 1 FROM projection_rebuild_runs r
           JOIN projection_rebuild_generations g ON g.run_id = r.run_id AND g.generation = r.current_generation
           WHERE r.run_id = route.shadow_run_id AND r.current_generation = enrolled.build_generation
               AND r.routing_enrolled AND g.state = 'BUILT') THEN
        RAISE EXCEPTION 'Projection enrollment requires a frozen completed rebuild.' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'projection_route_audit' THEN
        decision := NEW;
        IF decision.revision > route.revision OR decision.run_id <> route.shadow_run_id
           OR decision.build_generation <> enrolled.build_generation THEN
            RAISE EXCEPTION 'Projection decisions must belong to the enrolled route history.' USING ERRCODE = '23514';
        END IF;
        IF decision.revision > 1 THEN
            SELECT * INTO previous FROM projection_route_audit WHERE revision = decision.expected_revision;
            IF NOT FOUND OR previous.run_id <> decision.run_id
               OR previous.build_generation <> decision.build_generation OR previous.action = decision.action THEN
                RAISE EXCEPTION 'Projection decisions must continue the prior route revision.' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER projection_route_consistent AFTER INSERT OR UPDATE ON projection_routes
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_route_consistency();
CREATE CONSTRAINT TRIGGER projection_route_audit_consistent AFTER INSERT ON projection_route_audit
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_route_consistency();

CREATE FUNCTION check_projection_enrollment_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_run UUID;
    enrolled BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'projection_routes' THEN target_run := NEW.shadow_run_id;
    ELSE target_run := NEW.run_id; END IF;
    SELECT routing_enrolled INTO enrolled FROM projection_rebuild_runs WHERE run_id = target_run;
    IF enrolled IS DISTINCT FROM EXISTS (SELECT 1 FROM projection_routes WHERE shadow_run_id = target_run) THEN
        RAISE EXCEPTION 'Projection enrollment flag and its route must commit together.' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER projection_enrollment_run_consistent AFTER INSERT OR UPDATE ON projection_rebuild_runs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_enrollment_consistency();
CREATE CONSTRAINT TRIGGER projection_enrollment_route_consistent AFTER INSERT OR UPDATE ON projection_routes
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_projection_enrollment_consistency();

-- Enrollment changes the shadow from a frozen rebuild target into a maintained live view.
-- Its manifest remains historical evidence; later rebuild mutations must use a different run.
CREATE FUNCTION protect_enrolled_projection_rebuild() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_run UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN target_run := OLD.run_id; ELSE target_run := NEW.run_id; END IF;
    IF EXISTS (SELECT 1 FROM projection_routes WHERE shadow_run_id = target_run) THEN
        RAISE EXCEPTION 'An enrolled projection rebuild is frozen.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER enrolled_projection_run_frozen BEFORE UPDATE OR DELETE ON projection_rebuild_runs
FOR EACH ROW EXECUTE FUNCTION protect_enrolled_projection_rebuild();
CREATE TRIGGER enrolled_projection_generation_frozen BEFORE INSERT OR UPDATE OR DELETE ON projection_rebuild_generations
FOR EACH ROW EXECUTE FUNCTION protect_enrolled_projection_rebuild();
CREATE TRIGGER enrolled_projection_item_frozen BEFORE INSERT OR UPDATE OR DELETE ON projection_rebuild_items
FOR EACH ROW EXECUTE FUNCTION protect_enrolled_projection_rebuild();
