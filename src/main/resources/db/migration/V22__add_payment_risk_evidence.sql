-- Advisory Redis admission is separate from financial idempotency and journal authority.
CREATE TABLE payment_risk_intents (
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    id UUID NOT NULL CHECK(id<>'00000000-0000-0000-0000-000000000000'::uuid),
    idempotency_key VARCHAR(255) COLLATE "C" NOT NULL CHECK(idempotency_key ~ '^[!-~]{1,255}$'),
    request_key_hash VARCHAR(64) NOT NULL CHECK(request_key_hash=encode(sha256(convert_to(idempotency_key,'UTF8')),'hex')),
    request_fingerprint VARCHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
    amount NUMERIC(19,2) NOT NULL CHECK(amount>0),
    currency VARCHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(created_at)),
    PRIMARY KEY(merchant_id,idempotency_key),
    UNIQUE(merchant_id,id)
);
CREATE TABLE payment_risk_decisions (
    merchant_id UUID NOT NULL,
    id UUID NOT NULL CHECK(id<>'00000000-0000-0000-0000-000000000000'::uuid),
    intent_id UUID NOT NULL,
    outcome VARCHAR(12) NOT NULL CHECK(outcome IN ('ALLOW','DENY','THROTTLED','UNAVAILABLE')),
    reason VARCHAR(32) NOT NULL,
    policy_hash VARCHAR(64) NOT NULL CHECK(policy_hash ~ '^[0-9a-f]{64}$'),
    policy_currency VARCHAR(3) NOT NULL CHECK(policy_currency ~ '^[A-Z]{3}$'),
    maximum_amount NUMERIC(19,2) NOT NULL CHECK(maximum_amount>0),
    max_admissions INTEGER NOT NULL CHECK(max_admissions BETWEEN 1 AND 1000),
    window_seconds INTEGER NOT NULL CHECK(window_seconds BETWEEN 1 AND 3600),
    used_count BIGINT,
    remaining_millis BIGINT,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(observed_at)),
    PRIMARY KEY(merchant_id,id),
    FOREIGN KEY(merchant_id,intent_id) REFERENCES payment_risk_intents(merchant_id,id),
    CHECK((outcome='ALLOW' AND reason='VELOCITY_ALLOWED') OR (outcome='DENY' AND reason IN ('AMOUNT_LIMIT','CURRENCY_UNSUPPORTED'))
        OR (outcome='THROTTLED' AND reason='VELOCITY_LIMIT') OR (outcome='UNAVAILABLE' AND reason='REDIS_UNAVAILABLE')),
    CHECK((outcome IN ('ALLOW','THROTTLED') AND used_count BETWEEN 1 AND max_admissions AND remaining_millis BETWEEN 1 AND window_seconds*1000
        AND (outcome<>'THROTTLED' OR used_count=max_admissions)) IS TRUE
        OR (outcome IN ('DENY','UNAVAILABLE') AND used_count IS NULL AND remaining_millis IS NULL))
);
CREATE UNIQUE INDEX payment_risk_terminal ON payment_risk_decisions(merchant_id,intent_id) WHERE outcome IN ('ALLOW','DENY');
CREATE INDEX payment_risk_owned_page ON payment_risk_decisions(merchant_id,id);
CREATE FUNCTION check_payment_risk_policy() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE intent payment_risk_intents%ROWTYPE;
BEGIN
    SELECT * INTO STRICT intent FROM payment_risk_intents WHERE merchant_id=NEW.merchant_id AND id=NEW.intent_id;
    IF (NEW.reason='CURRENCY_UNSUPPORTED' AND intent.currency=NEW.policy_currency)
        OR (NEW.reason='AMOUNT_LIMIT' AND (intent.currency<>NEW.policy_currency OR intent.amount<=NEW.maximum_amount))
        OR (NEW.outcome<>'DENY' AND (intent.currency<>NEW.policy_currency OR intent.amount>NEW.maximum_amount)) THEN
        RAISE EXCEPTION 'Risk decision contradicts its amount/currency policy.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_risk_policy BEFORE INSERT ON payment_risk_decisions FOR EACH ROW EXECUTE FUNCTION check_payment_risk_policy();
CREATE FUNCTION protect_payment_risk_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Payment risk evidence is immutable.' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_payment_risk_intent BEFORE UPDATE OR DELETE ON payment_risk_intents FOR EACH ROW EXECUTE FUNCTION protect_payment_risk_evidence();
CREATE TRIGGER immutable_payment_risk_decision BEFORE UPDATE OR DELETE ON payment_risk_decisions FOR EACH ROW EXECUTE FUNCTION protect_payment_risk_evidence();
