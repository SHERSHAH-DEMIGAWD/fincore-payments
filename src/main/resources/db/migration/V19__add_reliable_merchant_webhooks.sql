CREATE TABLE merchant_webhook_subscriptions (
    subscription_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id) CHECK (merchant_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    create_request_id UUID NOT NULL,
    subscription_slot INTEGER NOT NULL CHECK (subscription_slot BETWEEN 1 AND 20),
    destination_url VARCHAR(512) NOT NULL CHECK (destination_url ~ '^https?://(127[.]0[.]0[.]1|\[::1\]):[1-9][0-9]{0,4}/[A-Za-z0-9/_-]*$'),
    master_key_id VARCHAR(64) NOT NULL CHECK (master_key_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    secret_nonce BYTEA NOT NULL CHECK (octet_length(secret_nonce)=12),
    secret_ciphertext BYTEA NOT NULL CHECK (octet_length(secret_ciphertext) BETWEEN 32 AND 512),
    state VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','PAUSED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    initial_backoff_ms BIGINT NOT NULL CHECK (initial_backoff_ms BETWEEN 1 AND 3600000),
    max_backoff_ms BIGINT NOT NULL CHECK (max_backoff_ms BETWEEN initial_backoff_ms AND 3600000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(updated_at)),
    UNIQUE(merchant_id,subscription_id), UNIQUE(merchant_id,create_request_id), UNIQUE(merchant_id,subscription_slot)
);

CREATE TABLE merchant_webhook_subscription_changes (
    merchant_id UUID NOT NULL, request_id UUID NOT NULL, subscription_id UUID NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision>=0),
    resulting_revision BIGINT NOT NULL CHECK (resulting_revision=expected_revision+1),
    state VARCHAR(8) NOT NULL CHECK (state IN ('ACTIVE','PAUSED')),
    reason VARCHAR(255) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 255),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(accepted_at)),
    mutation_xid VARCHAR(20) NOT NULL DEFAULT pg_current_xact_id()::text,
    PRIMARY KEY(merchant_id,request_id), UNIQUE(subscription_id,resulting_revision),
    FOREIGN KEY(merchant_id,subscription_id) REFERENCES merchant_webhook_subscriptions(merchant_id,subscription_id)
);

ALTER TABLE payment_outbox ADD CONSTRAINT outbox_merchant_delivery_identity UNIQUE(event_id,payment_id,payment_version);
CREATE TABLE merchant_webhook_deliveries (
    delivery_id UUID PRIMARY KEY, merchant_id UUID NOT NULL, subscription_id UUID NOT NULL,
    event_id UUID NOT NULL, payment_id UUID NOT NULL, payment_version BIGINT NOT NULL CHECK (payment_version>=0),
    event_type VARCHAR(32) NOT NULL,
    destination_url VARCHAR(512) NOT NULL,
    body BYTEA NOT NULL CHECK (octet_length(body) BETWEEN 1 AND 16384),
    body_sha256 VARCHAR(64) NOT NULL CHECK (body_sha256=encode(sha256(body),'hex')),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    initial_backoff_ms BIGINT NOT NULL CHECK (initial_backoff_ms BETWEEN 1 AND 3600000),
    max_backoff_ms BIGINT NOT NULL CHECK (max_backoff_ms BETWEEN initial_backoff_ms AND 3600000),
    state VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 1000),
    cycle_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cycle_attempts BETWEEN 0 AND max_attempts),
    retry_generation BIGINT NOT NULL DEFAULT 0 CHECK (retry_generation>=0),
    next_attempt_at TIMESTAMPTZ DEFAULT clock_timestamp(),
    lease_token UUID, lease_until TIMESTAMPTZ, delivered_at TIMESTAMPTZ,
    last_http_status INTEGER CHECK (last_http_status BETWEEN 100 AND 599), last_error_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(updated_at)),
    UNIQUE(merchant_id,delivery_id), UNIQUE(subscription_id,event_id), UNIQUE(subscription_id,payment_id,payment_version),
    FOREIGN KEY(merchant_id,subscription_id) REFERENCES merchant_webhook_subscriptions(merchant_id,subscription_id),
    FOREIGN KEY(merchant_id,payment_id) REFERENCES payments(merchant_id,id),
    FOREIGN KEY(event_id,payment_id,payment_version) REFERENCES payment_outbox(event_id,payment_id,payment_version),
    CHECK (((state='PENDING' AND cycle_attempts<max_attempts AND attempts<1000 AND lease_token IS NULL AND lease_until IS NULL
            AND next_attempt_at IS NOT NULL AND isfinite(next_attempt_at) AND delivered_at IS NULL)
        OR (state='IN_FLIGHT' AND attempts>0 AND cycle_attempts>0 AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND isfinite(lease_until) AND next_attempt_at IS NULL AND delivered_at IS NULL AND last_http_status IS NULL AND last_error_code IS NULL)
        OR (state='DELIVERED' AND attempts>0 AND cycle_attempts>0 AND lease_token IS NULL AND lease_until IS NULL
            AND next_attempt_at IS NULL AND delivered_at IS NOT NULL AND isfinite(delivered_at) AND last_http_status BETWEEN 200 AND 299 AND last_error_code IS NULL)
        OR (state='DEAD' AND (cycle_attempts>=max_attempts OR attempts>=1000) AND lease_token IS NULL AND lease_until IS NULL
            AND next_attempt_at IS NULL AND delivered_at IS NULL AND last_error_code IS NOT NULL)) IS TRUE)
);
CREATE INDEX merchant_webhook_delivery_due ON merchant_webhook_deliveries(created_at,delivery_id) WHERE state IN ('PENDING','IN_FLIGHT');
CREATE INDEX merchant_webhook_delivery_order ON merchant_webhook_deliveries(subscription_id,payment_id,payment_version,state);
CREATE INDEX merchant_webhook_delivery_owned ON merchant_webhook_deliveries(merchant_id,delivery_id);

CREATE TABLE merchant_webhook_attempts (
    attempt_id UUID PRIMARY KEY, merchant_id UUID NOT NULL, delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 1000),
    retry_generation BIGINT NOT NULL CHECK (retry_generation>=0),
    lease_token UUID NOT NULL, signed_at BIGINT NOT NULL CHECK (signed_at>0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(started_at)),
    lease_until TIMESTAMPTZ NOT NULL CHECK (isfinite(lease_until)),
    state VARCHAR(12) NOT NULL DEFAULT 'IN_FLIGHT', finished_at TIMESTAMPTZ,
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599), error_code VARCHAR(40),
    UNIQUE(delivery_id,attempt_number),
    FOREIGN KEY(merchant_id,delivery_id) REFERENCES merchant_webhook_deliveries(merchant_id,delivery_id),
    CHECK (((state='IN_FLIGHT' AND finished_at IS NULL AND http_status IS NULL AND error_code IS NULL)
        OR (state='DELIVERED' AND finished_at IS NOT NULL AND http_status BETWEEN 200 AND 299 AND error_code IS NULL)
        OR (state='FAILED' AND finished_at IS NOT NULL AND error_code IN ('HTTP_REJECTED','NETWORK_UNCONFIRMED','DESTINATION_NOT_ALLOWED','SIGNING_KEY_UNAVAILABLE'))
        OR (state='ABANDONED' AND finished_at IS NOT NULL AND http_status IS NULL AND error_code='LEASE_EXPIRED')) IS TRUE),
    CHECK (finished_at IS NULL OR (isfinite(finished_at) AND finished_at>=started_at)), CHECK (lease_until>started_at)
);

CREATE TABLE merchant_webhook_retries (
    merchant_id UUID NOT NULL, request_id UUID NOT NULL, delivery_id UUID NOT NULL,
    expected_generation BIGINT NOT NULL CHECK (expected_generation>=0),
    resulting_generation BIGINT NOT NULL CHECK (resulting_generation=expected_generation+1),
    reason VARCHAR(255) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 255),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(accepted_at)),
    mutation_xid VARCHAR(20) NOT NULL DEFAULT pg_current_xact_id()::text,
    PRIMARY KEY(merchant_id,request_id), UNIQUE(delivery_id,resulting_generation),
    FOREIGN KEY(merchant_id,delivery_id) REFERENCES merchant_webhook_deliveries(merchant_id,delivery_id)
);

CREATE FUNCTION protect_merchant_webhook_audit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Merchant webhook audit history is immutable.' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER merchant_webhook_changes_immutable BEFORE UPDATE OR DELETE ON merchant_webhook_subscription_changes
    FOR EACH ROW EXECUTE FUNCTION protect_merchant_webhook_audit();
CREATE TRIGGER merchant_webhook_retries_immutable BEFORE UPDATE OR DELETE ON merchant_webhook_retries
    FOR EACH ROW EXECUTE FUNCTION protect_merchant_webhook_audit();

CREATE FUNCTION protect_merchant_webhook_subscription() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Subscription history cannot be deleted.' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-ARRAY['state','revision','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','revision','updated_at'])
        OR NEW.state=OLD.state OR NEW.revision<>OLD.revision+1 OR NOT EXISTS (
            SELECT 1 FROM merchant_webhook_subscription_changes c WHERE c.subscription_id=OLD.subscription_id
                AND c.merchant_id=OLD.merchant_id AND c.expected_revision=OLD.revision AND c.resulting_revision=NEW.revision
                AND c.state=NEW.state AND c.mutation_xid=pg_current_xact_id()::text) THEN
        RAISE EXCEPTION 'Subscription state requires its matching change decision.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER merchant_webhook_subscription_protected BEFORE UPDATE OR DELETE ON merchant_webhook_subscriptions
    FOR EACH ROW EXECUTE FUNCTION protect_merchant_webhook_subscription();

CREATE FUNCTION enroll_merchant_webhooks() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE target merchant_webhook_subscriptions; owner_id UUID; identity UUID; payload_bytes BYTEA;
BEGIN
    SELECT merchant_id INTO owner_id FROM payments WHERE id=NEW.payment_id;
    FOR target IN SELECT * FROM merchant_webhook_subscriptions WHERE merchant_id=owner_id AND state='ACTIVE'
        ORDER BY subscription_id FOR SHARE LOOP
        identity:=gen_random_uuid();
        payload_bytes:=convert_to('{"version":1,"deliveryId":"'||identity::text||'","subscriptionId":"'||target.subscription_id::text
            ||'","event":'||NEW.payload||'}','UTF8');
        INSERT INTO merchant_webhook_deliveries(delivery_id,merchant_id,subscription_id,event_id,payment_id,payment_version,event_type,
            destination_url,body,body_sha256,max_attempts,initial_backoff_ms,max_backoff_ms)
        VALUES(identity,owner_id,target.subscription_id,NEW.event_id,NEW.payment_id,NEW.payment_version,NEW.event_type,
            target.destination_url,payload_bytes,encode(sha256(payload_bytes),'hex'),target.max_attempts,target.initial_backoff_ms,target.max_backoff_ms);
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER merchant_webhook_enrollment AFTER INSERT ON payment_outbox FOR EACH ROW EXECUTE FUNCTION enroll_merchant_webhooks();

CREATE FUNCTION protect_merchant_webhook_delivery() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original_event payment_outbox; subscription merchant_webhook_subscriptions;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Delivery history cannot be deleted.' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' THEN
        SELECT * INTO original_event FROM payment_outbox WHERE event_id=NEW.event_id;
        SELECT * INTO subscription FROM merchant_webhook_subscriptions WHERE subscription_id=NEW.subscription_id;
        IF pg_trigger_depth()<2 OR NEW.state<>'PENDING' OR NEW.attempts<>0 OR NEW.cycle_attempts<>0 OR NEW.retry_generation<>0
            OR original_event.event_id IS NULL OR subscription.subscription_id IS NULL OR subscription.state<>'ACTIVE'
            OR ROW(NEW.merchant_id,NEW.destination_url,NEW.max_attempts,NEW.initial_backoff_ms,NEW.max_backoff_ms)
                IS DISTINCT FROM ROW(subscription.merchant_id,subscription.destination_url,subscription.max_attempts,subscription.initial_backoff_ms,subscription.max_backoff_ms)
            OR NEW.event_type<>original_event.event_type OR convert_from(NEW.body,'UTF8')<>
                '{"version":1,"deliveryId":"'||NEW.delivery_id::text||'","subscriptionId":"'||NEW.subscription_id::text||'","event":'||original_event.payload||'}' THEN
            RAISE EXCEPTION 'Delivery must enroll the exact new outbox event and subscription.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.delivery_id,NEW.merchant_id,NEW.subscription_id,NEW.event_id,NEW.payment_id,NEW.payment_version,NEW.event_type,
        NEW.destination_url,NEW.body,NEW.body_sha256,NEW.max_attempts,NEW.initial_backoff_ms,NEW.max_backoff_ms,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.delivery_id,OLD.merchant_id,OLD.subscription_id,OLD.event_id,OLD.payment_id,OLD.payment_version,OLD.event_type,
        OLD.destination_url,OLD.body,OLD.body_sha256,OLD.max_attempts,OLD.initial_backoff_ms,OLD.max_backoff_ms,OLD.created_at) THEN
        RAISE EXCEPTION 'Delivery identity, intent and payload are immutable.' USING ERRCODE='23514';
    END IF;
    IF NEW.state='IN_FLIGHT' AND OLD.state IN ('PENDING','IN_FLIGHT') AND NEW.retry_generation=OLD.retry_generation
        AND NEW.attempts=OLD.attempts+1 AND NEW.cycle_attempts=OLD.cycle_attempts+1
        AND NEW.lease_token IS DISTINCT FROM OLD.lease_token AND (OLD.state='PENDING' OR OLD.lease_until<=clock_timestamp()) THEN
        NULL;
    ELSIF OLD.state='IN_FLIGHT' AND NEW.state IN ('PENDING','DELIVERED','DEAD') AND NEW.attempts=OLD.attempts
        AND NEW.cycle_attempts=OLD.cycle_attempts AND NEW.retry_generation=OLD.retry_generation THEN
        NULL;
    ELSIF OLD.state='DEAD' AND NEW.state='PENDING' AND NEW.attempts=OLD.attempts AND NEW.cycle_attempts=0
        AND NEW.retry_generation=OLD.retry_generation+1 AND EXISTS (
            SELECT 1 FROM merchant_webhook_retries r WHERE r.delivery_id=OLD.delivery_id AND r.merchant_id=OLD.merchant_id
                AND r.expected_generation=OLD.retry_generation AND r.resulting_generation=NEW.retry_generation
                AND r.mutation_xid=pg_current_xact_id()::text) THEN
        NULL;
    ELSE RAISE EXCEPTION 'Delivery progress transition is invalid.' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER merchant_webhook_delivery_protected BEFORE INSERT OR UPDATE OR DELETE ON merchant_webhook_deliveries
    FOR EACH ROW EXECUTE FUNCTION protect_merchant_webhook_delivery();

CREATE FUNCTION protect_merchant_webhook_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE delivery merchant_webhook_deliveries;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Attempt history cannot be deleted.' USING ERRCODE='23514'; END IF;
    SELECT * INTO delivery FROM merchant_webhook_deliveries WHERE delivery_id=NEW.delivery_id;
    IF TG_OP='INSERT' THEN
        IF delivery.delivery_id IS NULL OR delivery.state<>'IN_FLIGHT' OR NEW.state<>'IN_FLIGHT'
            OR NEW.attempt_number<>delivery.attempts OR NEW.retry_generation<>delivery.retry_generation
            OR NEW.merchant_id<>delivery.merchant_id OR NEW.lease_token IS DISTINCT FROM delivery.lease_token
            OR NEW.lease_until IS DISTINCT FROM delivery.lease_until THEN
            RAISE EXCEPTION 'Attempt must match the newly claimed delivery.' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW)-ARRAY['state','finished_at','http_status','error_code']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','finished_at','http_status','error_code'])
        OR OLD.state<>'IN_FLIGHT' OR NEW.state='IN_FLIGHT' THEN
        RAISE EXCEPTION 'Finished attempt history is immutable.' USING ERRCODE='23514';
    END IF;
    SELECT * INTO delivery FROM merchant_webhook_deliveries WHERE delivery_id=OLD.delivery_id;
    IF delivery.state<>'IN_FLIGHT' OR delivery.lease_token IS DISTINCT FROM OLD.lease_token
        OR (NEW.state='ABANDONED' AND delivery.lease_until>clock_timestamp()) THEN
        RAISE EXCEPTION 'Attempt completion does not own the current lease.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER merchant_webhook_attempt_protected BEFORE INSERT OR UPDATE OR DELETE ON merchant_webhook_attempts
    FOR EACH ROW EXECUTE FUNCTION protect_merchant_webhook_attempt();
CREATE UNIQUE INDEX merchant_webhook_one_live_attempt ON merchant_webhook_attempts(delivery_id) WHERE state='IN_FLIGHT';

CREATE FUNCTION check_merchant_webhook_delivery_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE d merchant_webhook_deliveries; a merchant_webhook_attempts; total BIGINT; cycle BIGINT;
BEGIN
    SELECT * INTO d FROM merchant_webhook_deliveries WHERE delivery_id=NEW.delivery_id;
    SELECT count(*),count(*) FILTER (WHERE retry_generation=d.retry_generation) INTO total,cycle FROM merchant_webhook_attempts WHERE delivery_id=d.delivery_id;
    IF total<>d.attempts OR cycle<>d.cycle_attempts THEN RAISE EXCEPTION 'Delivery counters must match its durable attempts.' USING ERRCODE='23514'; END IF;
    IF d.attempts=0 THEN RETURN NULL; END IF;
    SELECT * INTO a FROM merchant_webhook_attempts WHERE delivery_id=d.delivery_id AND attempt_number=d.attempts;
    IF a.attempt_id IS NULL OR (d.state='IN_FLIGHT' AND (a.state<>'IN_FLIGHT' OR a.lease_token IS DISTINCT FROM d.lease_token OR a.lease_until IS DISTINCT FROM d.lease_until))
        OR (d.state='DELIVERED' AND (a.state<>'DELIVERED' OR d.last_http_status IS DISTINCT FROM a.http_status))
        OR (d.state='DEAD' AND (a.state NOT IN ('FAILED','ABANDONED') OR d.last_error_code IS DISTINCT FROM a.error_code OR d.last_http_status IS DISTINCT FROM a.http_status))
        OR (d.state='PENDING' AND d.cycle_attempts>0 AND (a.state<>'FAILED' OR d.last_error_code IS DISTINCT FROM a.error_code OR d.last_http_status IS DISTINCT FROM a.http_status)) THEN
        RAISE EXCEPTION 'Delivery and latest attempt must agree at commit.' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER merchant_webhook_delivery_commit AFTER INSERT OR UPDATE ON merchant_webhook_deliveries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_merchant_webhook_delivery_commit();
CREATE CONSTRAINT TRIGGER merchant_webhook_attempt_commit AFTER INSERT OR UPDATE ON merchant_webhook_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_merchant_webhook_delivery_commit();

CREATE FUNCTION check_merchant_webhook_decision_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.mutation_xid<>pg_current_xact_id()::text THEN RAISE EXCEPTION 'Webhook decision transaction differs.' USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME='merchant_webhook_subscription_changes' THEN
        IF NOT EXISTS (SELECT 1 FROM merchant_webhook_subscriptions s WHERE s.subscription_id=NEW.subscription_id
            AND s.merchant_id=NEW.merchant_id AND s.revision=NEW.resulting_revision AND s.state=NEW.state) THEN
            RAISE EXCEPTION 'Subscription change must commit with its state.' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM merchant_webhook_deliveries d WHERE d.delivery_id=NEW.delivery_id
            AND d.merchant_id=NEW.merchant_id AND d.retry_generation=NEW.resulting_generation AND d.state='PENDING' AND d.cycle_attempts=0) THEN
            RAISE EXCEPTION 'Delivery retry must commit before its next attempt.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER merchant_webhook_change_commit AFTER INSERT ON merchant_webhook_subscription_changes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_merchant_webhook_decision_commit();
CREATE CONSTRAINT TRIGGER merchant_webhook_retry_commit AFTER INSERT ON merchant_webhook_retries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_merchant_webhook_decision_commit();
