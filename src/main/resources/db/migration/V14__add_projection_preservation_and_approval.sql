-- Saved server-verified evidence and authenticated acknowledgements; no deletion authority.
CREATE TABLE projection_preservations (
    request_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES projection_purge_plans(request_id),
    format_version INTEGER NOT NULL CHECK (format_version=1),
    expected_revision BIGINT NOT NULL CHECK (expected_revision>=0),
    preserved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor VARCHAR(100) NOT NULL CHECK (length(btrim(actor))>0 AND actor !~ '[[:cntrl:]]' AND actor !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    artifact_count INTEGER NOT NULL CHECK (artifact_count BETWEEN 1 AND 1000),
    export_bytes BIGINT NOT NULL CHECK (export_bytes BETWEEN 1 AND 8388608),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    export_json TEXT NOT NULL,
    UNIQUE(request_id,plan_id,sha256),
    CHECK (octet_length(convert_to(export_json,'UTF8'))=export_bytes),
    CHECK (encode(sha256(convert_to(export_json,'UTF8')),'hex')=sha256),
    CHECK ((jsonb_typeof(export_json::jsonb)='object'
        AND export_json::jsonb->'formatVersion'=to_jsonb(1)
        AND export_json::jsonb->>'preservationId'=request_id::text
        AND export_json::jsonb->'plan'->>'requestId'=plan_id::text
        AND jsonb_typeof(export_json::jsonb->'records')='array'
        AND jsonb_array_length(export_json::jsonb->'records')=artifact_count) IS TRUE)
);

CREATE TABLE projection_preservation_approvals (
    request_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES projection_purge_plans(request_id),
    preservation_id UUID NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision>=0),
    export_sha256 VARCHAR(64) NOT NULL,
    preservation_acknowledged BOOLEAN NOT NULL CHECK (preservation_acknowledged),
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    actor VARCHAR(100) NOT NULL CHECK (length(btrim(actor))>0 AND actor !~ '[[:cntrl:]]' AND actor !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]'),
    FOREIGN KEY (preservation_id,plan_id,export_sha256) REFERENCES projection_preservations(request_id,plan_id,sha256)
);

CREATE FUNCTION protect_projection_preservation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Preservation and approval evidence is immutable.' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM projection_purge_plans p WHERE p.request_id=NEW.plan_id
            AND p.expected_revision=NEW.expected_revision)
        OR NEW.expected_revision<>coalesce((SELECT revision FROM projection_routes WHERE route_key),0) THEN
        RAISE EXCEPTION 'Preservation requires the planned current route revision.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER projection_preservation_guard BEFORE INSERT OR UPDATE OR DELETE ON projection_preservations
    FOR EACH ROW EXECUTE FUNCTION protect_projection_preservation();
CREATE TRIGGER projection_preservation_approval_guard BEFORE INSERT OR UPDATE OR DELETE ON projection_preservation_approvals
    FOR EACH ROW EXECUTE FUNCTION protect_projection_preservation();
