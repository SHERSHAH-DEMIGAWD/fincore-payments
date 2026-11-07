-- Keep one warm shadow at a time, with permanent enrollment and retirement evidence.
-- Existing V10 decisions retain their identities, timestamps and meaning.
ALTER TABLE projection_route_audit ADD COLUMN previous_run_id UUID REFERENCES projection_rebuild_runs(run_id);
DROP TRIGGER projection_route_audit_immutable ON projection_route_audit;
UPDATE projection_route_audit SET previous_run_id = run_id WHERE revision > 1;
CREATE TRIGGER projection_route_audit_immutable BEFORE UPDATE OR DELETE ON projection_route_audit
FOR EACH ROW EXECUTE FUNCTION protect_projection_route_audit();

ALTER TABLE projection_route_audit DROP CONSTRAINT projection_route_audit_action_check;
ALTER TABLE projection_route_audit DROP CONSTRAINT projection_route_audit_check1;
ALTER TABLE projection_route_audit ADD CONSTRAINT projection_route_action_check
    CHECK (action IN ('PROMOTE','ROLLBACK','REPLACE','RETIRE'));
ALTER TABLE projection_route_audit ADD CONSTRAINT projection_route_action_target_check
    CHECK ((action IN ('PROMOTE','REPLACE') AND active_consumer = 'shadow-' || run_id::text)
        OR (action IN ('ROLLBACK','RETIRE') AND active_consumer = 'payment-projection-v1'));

ALTER TABLE projection_routes ALTER COLUMN shadow_run_id DROP NOT NULL;
ALTER TABLE projection_routes DROP CONSTRAINT projection_routes_check;
ALTER TABLE projection_routes ADD CONSTRAINT projection_route_current_target_check
    CHECK (active_consumer = 'payment-projection-v1'
        OR (shadow_run_id IS NOT NULL AND active_consumer = 'shadow-' || shadow_run_id::text));

CREATE OR REPLACE FUNCTION protect_projection_route() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Projection routing history cannot be removed.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.revision <> 1 OR NEW.shadow_run_id IS NULL
           OR NEW.active_consumer <> 'shadow-' || NEW.shadow_run_id::text THEN
            RAISE EXCEPTION 'Projection routing starts with a promotion.' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.route_key IS DISTINCT FROM OLD.route_key
       OR NEW.revision::numeric <> OLD.revision::numeric + 1
       OR (NEW.active_consumer IS NOT DISTINCT FROM OLD.active_consumer
           AND NEW.shadow_run_id IS NOT DISTINCT FROM OLD.shadow_run_id)
       OR NEW.last_request_id IS NOT DISTINCT FROM OLD.last_request_id THEN
        RAISE EXCEPTION 'Projection routing must advance once through a lifecycle decision.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION check_projection_route_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    route projection_routes%ROWTYPE;
    decision projection_route_audit%ROWTYPE;
    previous projection_route_audit%ROWTYPE;
    previous_target UUID;
    target UUID;
BEGIN
    SELECT * INTO route FROM projection_routes WHERE route_key;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Projection decisions require their committed route.' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO decision FROM projection_route_audit WHERE request_id = route.last_request_id;
    target := CASE WHEN decision.action = 'RETIRE' THEN NULL ELSE decision.run_id END;
    IF NOT FOUND OR decision.revision <> route.revision
       OR target IS DISTINCT FROM route.shadow_run_id OR decision.active_consumer <> route.active_consumer THEN
        RAISE EXCEPTION 'Projection route and its current audit must agree.' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'projection_route_audit' THEN
        decision := NEW;
        IF decision.revision > route.revision THEN
            RAISE EXCEPTION 'Projection audit cannot run ahead of the route.' USING ERRCODE = '23514';
        END IF;
        IF decision.revision = 1 THEN
            IF decision.action <> 'PROMOTE' OR decision.previous_run_id IS NOT NULL THEN
                RAISE EXCEPTION 'First projection decision must enroll a shadow.' USING ERRCODE = '23514';
            END IF;
            previous_target := NULL;
        ELSE
            SELECT * INTO previous FROM projection_route_audit WHERE revision = decision.expected_revision;
            previous_target := CASE WHEN previous.action = 'RETIRE' THEN NULL ELSE previous.run_id END;
            IF NOT FOUND OR decision.previous_run_id IS DISTINCT FROM previous_target THEN
                RAISE EXCEPTION 'Projection lifecycle must continue its prior target and revision.' USING ERRCODE = '23514';
            END IF;
            IF decision.action = 'PROMOTE' THEN
                IF previous.active_consumer <> 'payment-projection-v1'
                   OR (previous_target IS NOT NULL AND previous_target <> decision.run_id) THEN
                    RAISE EXCEPTION 'Promotion selects the maintained shadow or enrolls after retirement.' USING ERRCODE = '23514';
                END IF;
            ELSIF decision.action = 'ROLLBACK' THEN
                IF previous_target IS DISTINCT FROM decision.run_id
                   OR previous.active_consumer <> 'shadow-' || decision.run_id::text THEN
                    RAISE EXCEPTION 'Rollback selects baseline from the active shadow.' USING ERRCODE = '23514';
                END IF;
            ELSIF decision.action = 'REPLACE' THEN
                IF previous_target IS NULL OR previous_target = decision.run_id
                   OR previous.active_consumer <> 'payment-projection-v1' THEN
                    RAISE EXCEPTION 'Replacement requires baseline reads and a different shadow.' USING ERRCODE = '23514';
                END IF;
            ELSIF decision.action = 'RETIRE' THEN
                IF previous_target IS DISTINCT FROM decision.run_id
                   OR previous.active_consumer <> 'payment-projection-v1' THEN
                    RAISE EXCEPTION 'Retirement requires baseline reads and the maintained shadow.' USING ERRCODE = '23514';
                END IF;
            END IF;
        END IF;
        -- A target can be enrolled once. Its generation remains frozen after retirement.
        IF decision.action IN ('PROMOTE','REPLACE') AND previous_target IS DISTINCT FROM decision.run_id THEN
            IF EXISTS (SELECT 1 FROM projection_route_audit a WHERE a.run_id = decision.run_id
                       AND a.action IN ('PROMOTE','REPLACE') AND a.revision < decision.revision) THEN
                RAISE EXCEPTION 'Retired projection targets cannot be enrolled again.' USING ERRCODE = '23514';
            END IF;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM projection_rebuild_runs r
            JOIN projection_rebuild_generations g ON g.run_id = r.run_id AND g.generation = r.current_generation
            WHERE r.run_id = decision.run_id AND r.routing_enrolled
                AND r.current_generation = decision.build_generation AND g.state = 'BUILT') THEN
            RAISE EXCEPTION 'Projection decisions require their frozen completed rebuild.' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

-- routing_enrolled means ever enrolled, not currently maintained. It is irreversible.
CREATE OR REPLACE FUNCTION check_projection_enrollment_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_run UUID;
    enrolled BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'projection_routes' THEN target_run := NEW.shadow_run_id;
    ELSE target_run := NEW.run_id; END IF;
    IF target_run IS NULL THEN RETURN NULL; END IF;
    SELECT routing_enrolled INTO enrolled FROM projection_rebuild_runs WHERE run_id = target_run;
    IF enrolled IS DISTINCT FROM EXISTS (SELECT 1 FROM projection_route_audit
        WHERE run_id = target_run AND action IN ('PROMOTE','REPLACE')) THEN
        RAISE EXCEPTION 'Projection enrollment requires permanent routing evidence.' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION protect_enrolled_projection_rebuild() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    target_run UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN target_run := OLD.run_id; ELSE target_run := NEW.run_id; END IF;
    IF EXISTS (SELECT 1 FROM projection_rebuild_runs WHERE run_id = target_run AND routing_enrolled) THEN
        RAISE EXCEPTION 'An ever-enrolled projection rebuild remains frozen.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE INDEX projection_route_target_history ON projection_route_audit(run_id,revision);
