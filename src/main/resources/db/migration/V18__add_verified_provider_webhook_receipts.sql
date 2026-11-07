CREATE TABLE provider_webhook_receipts (
    receipt_id UUID PRIMARY KEY,
    provider_instance_id UUID NOT NULL,
    event_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('AUTHORIZE','CAPTURE','REFUND')),
    outcome VARCHAR(8) NOT NULL CHECK (outcome IN ('APPROVED','DECLINED')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount>0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    provider_payment_id VARCHAR(100) NOT NULL,
    provider_operation_id VARCHAR(100) NOT NULL,
    matched_operation_id UUID REFERENCES payment_provider_operations(operation_id),
    raw_body BYTEA NOT NULL CHECK (octet_length(raw_body) BETWEEN 1 AND 8192),
    body_sha256 VARCHAR(64) NOT NULL CHECK (body_sha256=encode(sha256(raw_body),'hex')),
    key_id VARCHAR(64) NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    signed_at BIGINT NOT NULL CHECK (signed_at>0),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(received_at)),
    processing_xid VARCHAR(20) NOT NULL DEFAULT pg_current_xact_id()::text,
    disposition VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    rejection_code VARCHAR(40),
    UNIQUE(provider_instance_id,event_id),
    CHECK (((disposition='RECEIVED' AND rejection_code IS NULL)
        OR (disposition IN ('CONFIRMED','ALREADY_RECORDED') AND matched_operation_id IS NOT NULL AND rejection_code IS NULL)
        OR (disposition='REQUIRES_REVIEW' AND matched_operation_id IS NOT NULL AND rejection_code='LOCAL_STATE_CONFLICT')
        OR (disposition='UNMATCHED' AND matched_operation_id IS NULL AND rejection_code='OPERATION_NOT_FOUND')
        OR (disposition='REJECTED' AND rejection_code IN ('INTENT_MISMATCH','OUTCOME_CONFLICT','NOT_DISPATCHED'))) IS TRUE),
    CHECK (((convert_from(raw_body,'UTF8')::jsonb->>'eventId')::uuid=event_id
        AND (convert_from(raw_body,'UTF8')::jsonb->>'version')='1'
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,version}')='1'
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,instanceId}')::uuid=provider_instance_id
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,operationId}')::uuid=operation_id
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,paymentId}')::uuid=payment_id
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,merchantId}')::uuid=merchant_id
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,action}')=action
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,outcome}')=outcome
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,amount}')::numeric=amount
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,currency}')=currency
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,providerPaymentId}')=provider_payment_id
        AND (convert_from(raw_body,'UTF8')::jsonb#>>'{result,providerOperationId}')=provider_operation_id) IS TRUE)
);
CREATE INDEX provider_webhook_owned_page ON provider_webhook_receipts(merchant_id,payment_id,operation_id,receipt_id);

CREATE FUNCTION protect_provider_webhook_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Provider webhook evidence cannot be deleted.' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-ARRAY['disposition','rejection_code']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['disposition','rejection_code'])
        OR OLD.disposition<>'RECEIVED' OR NEW.disposition='RECEIVED'
        OR NEW.processing_xid<>pg_current_xact_id()::text THEN
        RAISE EXCEPTION 'Provider webhook evidence is immutable after processing.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER provider_webhook_receipt_protected BEFORE UPDATE OR DELETE ON provider_webhook_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_provider_webhook_receipt();

CREATE FUNCTION check_provider_webhook_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE r provider_webhook_receipts; o payment_provider_operations;
BEGIN
    SELECT * INTO r FROM provider_webhook_receipts WHERE receipt_id=NEW.receipt_id;
    IF r.disposition='RECEIVED' OR r.processing_xid<>pg_current_xact_id()::text THEN
        RAISE EXCEPTION 'Webhook receipt must finish in its insertion transaction.' USING ERRCODE='23514';
    END IF;
    IF r.matched_operation_id IS NOT NULL THEN
        SELECT * INTO o FROM payment_provider_operations WHERE operation_id=r.matched_operation_id;
        IF o.operation_id IS NULL OR ROW(o.operation_id,o.merchant_id,o.payment_id,o.provider_instance_id)
            IS DISTINCT FROM ROW(r.operation_id,r.merchant_id,r.payment_id,r.provider_instance_id) THEN
            RAISE EXCEPTION 'Webhook matched-operation scope differs.' USING ERRCODE='23514';
        END IF;
        IF r.disposition IN ('CONFIRMED','ALREADY_RECORDED','REQUIRES_REVIEW') AND (
            ROW(o.action,o.outcome,o.amount,o.currency,o.provider_payment_id,o.provider_operation_id)
                IS DISTINCT FROM ROW(r.action,r.outcome,r.amount,r.currency,r.provider_payment_id,r.provider_operation_id)
            OR (r.disposition IN ('CONFIRMED','ALREADY_RECORDED') AND o.state NOT IN ('APPLIED','DECLINED'))
            OR (r.disposition='REQUIRES_REVIEW' AND o.state<>'REQUIRES_REVIEW')) THEN
            RAISE EXCEPTION 'Webhook confirmation and operation must agree at commit.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER provider_webhook_commit AFTER INSERT OR UPDATE ON provider_webhook_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_provider_webhook_commit();

-- Preserve all V17 transitions. Add only a same-transaction, matched webhook confirmation
-- from UNKNOWN, without consuming another outbound attempt or weakening the financial guard.
CREATE OR REPLACE FUNCTION protect_provider_operation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
    ELSIF OLD.state='UNKNOWN' AND NEW.state IN ('APPLIED','DECLINED','REQUIRES_REVIEW') AND NEW.attempts=OLD.attempts
        AND EXISTS (SELECT 1 FROM provider_webhook_receipts r WHERE r.matched_operation_id=OLD.operation_id
            AND r.processing_xid=pg_current_xact_id()::text AND r.disposition='RECEIVED'
            AND ROW(r.provider_instance_id,r.operation_id,r.payment_id,r.merchant_id,r.action,r.outcome,r.amount,r.currency,
                r.provider_payment_id,r.provider_operation_id)
                IS NOT DISTINCT FROM ROW(NEW.provider_instance_id,NEW.operation_id,NEW.payment_id,NEW.merchant_id,NEW.action,NEW.outcome,
                    NEW.amount,NEW.currency,NEW.provider_payment_id,NEW.provider_operation_id)) THEN
        NULL;
    ELSE
        RAISE EXCEPTION 'Provider progress transition is invalid.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
