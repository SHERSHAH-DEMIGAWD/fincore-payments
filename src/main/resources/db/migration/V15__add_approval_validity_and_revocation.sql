-- Preserve accepted V14 audit fields. NULL validity marks legacy approvals for fresh review.
ALTER TABLE projection_preservation_approvals ADD COLUMN validity_minutes INTEGER;
ALTER TABLE projection_preservation_approvals ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE projection_preservation_approvals ADD CONSTRAINT projection_approval_validity CHECK (
    (validity_minutes IS NULL AND expires_at IS NULL)
    OR (validity_minutes IS NOT NULL AND expires_at IS NOT NULL
        AND validity_minutes BETWEEN 1 AND 1440
        AND isfinite(approved_at) AND isfinite(expires_at)
        AND expires_at=approved_at+validity_minutes*INTERVAL '1 minute')
);

CREATE FUNCTION require_new_projection_approval_validity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.validity_minutes IS NULL OR NEW.expires_at IS NULL THEN
        RAISE EXCEPTION 'New approvals require explicit bounded validity.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER projection_approval_requires_validity BEFORE INSERT ON projection_preservation_approvals
    FOR EACH ROW EXECUTE FUNCTION require_new_projection_approval_validity();

CREATE TABLE projection_approval_revocations (
    request_id UUID PRIMARY KEY,
    approval_id UUID NOT NULL UNIQUE REFERENCES projection_preservation_approvals(request_id),
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    actor VARCHAR(100) NOT NULL CHECK (length(btrim(actor))>0 AND actor !~ '[[:cntrl:]]' AND actor !~ '[\u2028\u2029]'),
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason))>0 AND reason !~ '[[:cntrl:]]' AND reason !~ '[\u2028\u2029]')
);
CREATE FUNCTION protect_projection_approval_revocation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval revocation history is immutable.' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER projection_approval_revocation_immutable BEFORE UPDATE OR DELETE ON projection_approval_revocations
    FOR EACH ROW EXECUTE FUNCTION protect_projection_approval_revocation();
