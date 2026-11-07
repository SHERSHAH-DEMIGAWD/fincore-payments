-- Planning evidence only. Existing retention/immutability guards are deliberately unchanged.
CREATE TABLE projection_purge_plans (
    request_id UUID PRIMARY KEY,
    plan_version INTEGER NOT NULL CHECK (plan_version = 1),
    run_id UUID NOT NULL,
    build_generation BIGINT NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
    retirement_revision BIGINT NOT NULL REFERENCES projection_route_audit(revision),
    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    review_after_days INTEGER NOT NULL CHECK (review_after_days BETWEEN 1 AND 3650),
    source_name VARCHAR(128) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    actor VARCHAR(100) NOT NULL CHECK (length(btrim(actor)) > 0 AND actor !~ '[[:cntrl:]]' AND actor !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    artifacts JSONB NOT NULL CHECK (jsonb_typeof(artifacts) = 'array' AND jsonb_array_length(artifacts) BETWEEN 1 AND 1000),
    preservation_requirements JSONB NOT NULL CHECK (jsonb_typeof(preservation_requirements) = 'array'
        AND jsonb_array_length(preservation_requirements) = 4),
    FOREIGN KEY (run_id,build_generation) REFERENCES projection_rebuild_generations(run_id,generation),
    CHECK (observed_at >= retired_at + review_after_days * INTERVAL '24 hours')
);

CREATE FUNCTION protect_projection_purge_plan() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Purge planning evidence is immutable.' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM projection_rebuild_runs r JOIN projection_route_audit a ON a.revision=NEW.retirement_revision
        WHERE r.run_id=NEW.run_id AND r.routing_enrolled AND r.current_generation=NEW.build_generation
          AND r.source_name=NEW.source_name AND r.topic=NEW.topic AND r.created_at<=NEW.retired_at
          AND a.occurred_at=NEW.retired_at
          AND ((a.action='RETIRE' AND a.run_id=r.run_id) OR (a.action='REPLACE' AND a.previous_run_id=r.run_id))
          AND NOT EXISTS (SELECT 1 FROM projection_routes p WHERE p.shadow_run_id=r.run_id)
    ) OR NEW.expected_revision <> coalesce((SELECT revision FROM projection_routes WHERE route_key),0) THEN
        RAISE EXCEPTION 'Purge plan requires current retired-target evidence.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER projection_purge_plan_guard BEFORE INSERT OR UPDATE OR DELETE ON projection_purge_plans
    FOR EACH ROW EXECUTE FUNCTION protect_projection_purge_plan();
