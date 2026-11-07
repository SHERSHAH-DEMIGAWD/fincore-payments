CREATE TABLE payment_provider_bindings (
    payment_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    provider_name VARCHAR(32) NOT NULL CHECK (provider_name='SANDBOX_HTTP_V1'),
    provider_instance_id UUID NOT NULL CHECK (provider_instance_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    provider_payment_id VARCHAR(100) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(merchant_id,payment_id,provider_instance_id),
    FOREIGN KEY (merchant_id,payment_id) REFERENCES payments(merchant_id,id),
    CHECK (provider_payment_id='' OR provider_payment_id='sandbox-pay-'||payment_id::text)
);
CREATE UNIQUE INDEX provider_remote_payment_identity ON payment_provider_bindings(provider_instance_id,provider_payment_id)
    WHERE provider_payment_id<>'';

CREATE TABLE payment_provider_operations (
    operation_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    provider_instance_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('AUTHORIZE','CAPTURE','REFUND')),
    sandbox_scenario VARCHAR(32) NOT NULL CHECK (sandbox_scenario IN ('APPROVE','DECLINE','TIMEOUT_AFTER_ACCEPT')),
    source_version BIGINT NOT NULL CHECK (source_version>=0),
    source_status VARCHAR(16) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount>0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    provider_payment_id_before VARCHAR(100) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'READY',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 1000),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    outcome VARCHAR(8),
    provider_payment_id VARCHAR(100) NOT NULL DEFAULT '',
    provider_operation_id VARCHAR(100) NOT NULL DEFAULT '',
    application_xid VARCHAR(20),
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(merchant_id,request_id),
    FOREIGN KEY (merchant_id,payment_id,provider_instance_id)
        REFERENCES payment_provider_bindings(merchant_id,payment_id,provider_instance_id),
    CHECK (source_status=CASE action WHEN 'AUTHORIZE' THEN 'CREATED' WHEN 'CAPTURE' THEN 'AUTHORIZED' ELSE 'CAPTURED' END),
    CHECK ((action='AUTHORIZE' AND provider_payment_id_before='') OR
        (action<>'AUTHORIZE' AND provider_payment_id_before='sandbox-pay-'||payment_id::text)),
    CHECK (isfinite(created_at) AND isfinite(updated_at)),
    CONSTRAINT provider_operation_shape CHECK ((
        (state='READY' AND attempts=0 AND lease_token IS NULL AND lease_until IS NULL AND outcome IS NULL
            AND provider_payment_id='' AND provider_operation_id='' AND application_xid IS NULL AND last_error_code IS NULL)
        OR (state='IN_FLIGHT' AND attempts>0 AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND isfinite(lease_until)
            AND outcome IS NULL AND provider_payment_id='' AND provider_operation_id='' AND application_xid IS NULL AND last_error_code IS NULL)
        OR (state='UNKNOWN' AND attempts>0 AND lease_token IS NULL AND lease_until IS NULL AND outcome IS NULL
            AND provider_payment_id='' AND provider_operation_id='' AND application_xid IS NULL AND last_error_code='PROVIDER_OUTCOME_UNCONFIRMED')
        OR (state='APPLIED' AND attempts>0 AND lease_token IS NULL AND lease_until IS NULL AND outcome='APPROVED'
            AND provider_payment_id='sandbox-pay-'||payment_id::text AND provider_operation_id='sandbox-op-'||operation_id::text
            AND application_xid IS NOT NULL AND last_error_code IS NULL)
        OR (state='DECLINED' AND attempts>0 AND lease_token IS NULL AND lease_until IS NULL AND outcome='DECLINED'
            AND provider_payment_id=provider_payment_id_before AND provider_operation_id='sandbox-op-'||operation_id::text
            AND ((action='AUTHORIZE' AND application_xid IS NOT NULL) OR (action<>'AUTHORIZE' AND application_xid IS NULL))
            AND last_error_code='PROVIDER_DECLINED')
        OR (state='REQUIRES_REVIEW' AND attempts>0 AND lease_token IS NULL AND lease_until IS NULL
            AND outcome IN ('APPROVED','DECLINED') AND provider_operation_id='sandbox-op-'||operation_id::text
            AND provider_payment_id=CASE outcome WHEN 'APPROVED' THEN 'sandbox-pay-'||payment_id::text ELSE provider_payment_id_before END
            AND application_xid IS NULL AND last_error_code='LOCAL_STATE_CONFLICT')
    ) IS TRUE)
);
CREATE UNIQUE INDEX one_unfinished_provider_operation ON payment_provider_operations(payment_id)
    WHERE state IN ('READY','IN_FLIGHT','UNKNOWN','REQUIRES_REVIEW');
CREATE UNIQUE INDEX one_provider_authorization ON payment_provider_operations(payment_id) WHERE action='AUTHORIZE';
CREATE INDEX provider_owned_operation_page ON payment_provider_operations(merchant_id,payment_id,operation_id);

CREATE FUNCTION provider_local_target(action_value TEXT,outcome_value TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN outcome_value='APPROVED' THEN
        CASE action_value WHEN 'AUTHORIZE' THEN 'AUTHORIZED' WHEN 'CAPTURE' THEN 'CAPTURED' WHEN 'REFUND' THEN 'REFUNDED' END
        WHEN action_value='AUTHORIZE' AND outcome_value='DECLINED' THEN 'FAILED' END
$$;

CREATE FUNCTION protect_provider_operation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Provider operation history cannot be deleted.' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.operation_id,NEW.merchant_id,NEW.request_id,NEW.payment_id,NEW.provider_instance_id,NEW.action,
        NEW.sandbox_scenario,NEW.source_version,NEW.source_status,NEW.amount,NEW.currency,NEW.provider_payment_id_before,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.operation_id,OLD.merchant_id,OLD.request_id,OLD.payment_id,OLD.provider_instance_id,OLD.action,
        OLD.sandbox_scenario,OLD.source_version,OLD.source_status,OLD.amount,OLD.currency,OLD.provider_payment_id_before,OLD.created_at) THEN
        RAISE EXCEPTION 'Provider command intent is immutable.' USING ERRCODE='23514';
    END IF;
    IF NEW.state='IN_FLIGHT' AND OLD.state IN ('READY','UNKNOWN','IN_FLIGHT') THEN
        IF NEW.attempts<>OLD.attempts+1 OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
            OR (OLD.state='IN_FLIGHT' AND OLD.lease_until>clock_timestamp()) THEN
            RAISE EXCEPTION 'Provider attempt lease is not available.' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.state='IN_FLIGHT' AND NEW.state IN ('UNKNOWN','APPLIED','DECLINED','REQUIRES_REVIEW') AND NEW.attempts=OLD.attempts THEN
        NULL;
    ELSE
        RAISE EXCEPTION 'Provider progress transition is invalid.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER provider_operation_protected BEFORE UPDATE OR DELETE ON payment_provider_operations
    FOR EACH ROW EXECUTE FUNCTION protect_provider_operation();

CREATE FUNCTION protect_provider_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Provider bindings cannot be deleted.' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.payment_id,NEW.merchant_id,NEW.provider_name,NEW.provider_instance_id,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.payment_id,OLD.merchant_id,OLD.provider_name,OLD.provider_instance_id,OLD.created_at)
        OR OLD.provider_payment_id<>'' OR NEW.provider_payment_id='' OR NOT EXISTS (
            SELECT 1 FROM payment_provider_operations o WHERE o.payment_id=NEW.payment_id AND o.action='AUTHORIZE'
                AND o.state='APPLIED' AND o.application_xid=pg_current_xact_id()::text
                AND o.provider_payment_id=NEW.provider_payment_id) THEN
        RAISE EXCEPTION 'Provider binding update is not a confirmed authorization.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER provider_binding_protected BEFORE UPDATE OR DELETE ON payment_provider_bindings
    FOR EACH ROW EXECUTE FUNCTION protect_provider_binding();

CREATE FUNCTION guard_provider_managed_payment() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM payment_provider_bindings WHERE payment_id=OLD.id) THEN
        IF ROW(NEW.id,NEW.merchant_id,NEW.merchant_reference,NEW.amount,NEW.currency,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.merchant_id,OLD.merchant_reference,OLD.amount,OLD.currency,OLD.created_at)
            OR NOT EXISTS (SELECT 1 FROM payment_provider_operations o
                WHERE o.payment_id=OLD.id AND o.state IN ('APPLIED','DECLINED')
                    AND o.application_xid=pg_current_xact_id()::text AND o.source_version=OLD.version
                    AND o.source_status=OLD.status AND NEW.version=OLD.version+1
                    AND NEW.status=provider_local_target(o.action,o.outcome)) THEN
            RAISE EXCEPTION 'Provider-managed payments require confirmed provider operations.' USING ERRCODE='P1701';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER provider_payment_guard BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION guard_provider_managed_payment();

CREATE FUNCTION check_provider_operation_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_op payment_provider_operations; target_status TEXT;
BEGIN
    SELECT * INTO current_op FROM payment_provider_operations WHERE operation_id=NEW.operation_id;
    IF current_op.state IN ('APPLIED','DECLINED') THEN
        target_status:=provider_local_target(current_op.action,current_op.outcome);
        IF target_status IS NOT NULL THEN
            IF current_op.application_xid<>pg_current_xact_id()::text OR NOT EXISTS (
                SELECT 1 FROM payments p JOIN payment_outbox e ON e.payment_id=p.id AND e.payment_version=p.version
                WHERE p.id=current_op.payment_id AND p.merchant_id=current_op.merchant_id
                    AND p.version=current_op.source_version+1 AND p.status=target_status
                    AND p.amount=current_op.amount AND p.currency=current_op.currency
                    AND e.event_type='PAYMENT_'||target_status) OR
                (current_op.action='AUTHORIZE' AND current_op.outcome='APPROVED' AND NOT EXISTS (
                    SELECT 1 FROM payment_provider_bindings b WHERE b.payment_id=current_op.payment_id
                        AND b.provider_payment_id=current_op.provider_payment_id)) THEN
                RAISE EXCEPTION 'Provider result and local payment/outbox must commit together.' USING ERRCODE='23514';
            END IF;
        ELSIF NOT EXISTS (SELECT 1 FROM payments p WHERE p.id=current_op.payment_id
            AND p.version=current_op.source_version AND p.status=current_op.source_status) THEN
            RAISE EXCEPTION 'Declined provider operation changed local payment state.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER provider_operation_commit AFTER INSERT OR UPDATE ON payment_provider_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_provider_operation_commit();

CREATE FUNCTION check_provider_binding_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM payment_provider_operations o JOIN payments p ON p.id=o.payment_id
        WHERE o.payment_id=NEW.payment_id AND o.action='AUTHORIZE' AND o.state='READY'
            AND p.status='CREATED' AND p.version=o.source_version AND p.amount=o.amount AND p.currency=o.currency) THEN
        RAISE EXCEPTION 'A provider binding requires its durable authorization command.' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER provider_binding_commit AFTER INSERT ON payment_provider_bindings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_provider_binding_commit();
