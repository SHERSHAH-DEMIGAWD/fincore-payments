CREATE TABLE merchants (
    id UUID PRIMARY KEY,
    username VARCHAR(100) COLLATE "C" NOT NULL UNIQUE
        CHECK (username ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'),
    password_hash VARCHAR(60),
    enabled BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT merchant_password_hash CHECK (password_hash IS NULL OR
        password_hash ~ '^\$2[aby]\$(0[4-9]|1[0-6])\$[./A-Za-z0-9]{53}$'),
    CONSTRAINT enabled_merchant_requires_password CHECK (NOT enabled OR password_hash IS NOT NULL),
    CONSTRAINT legacy_merchant_cannot_authenticate CHECK (
        id <> '00000000-0000-0000-0000-000000000000'::uuid OR
        (NOT enabled AND password_hash IS NULL))
);

-- Historical ownership is unknown. Do not assign old records to a newly provisioned merchant.
INSERT INTO merchants(id,username,enabled)
VALUES ('00000000-0000-0000-0000-000000000000','legacy-internal',false);

CREATE FUNCTION protect_merchant_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Merchant identities cannot be deleted.' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.id,NEW.username,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.username,OLD.created_at) THEN
        RAISE EXCEPTION 'Merchant identities are immutable.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER merchant_identity_immutable BEFORE UPDATE OR DELETE ON merchants
    FOR EACH ROW EXECUTE FUNCTION protect_merchant_identity();

-- Defaults preserve trusted local/legacy creation paths; the HTTP service supplies its principal's ID.
ALTER TABLE payments ADD COLUMN merchant_id UUID NOT NULL
    DEFAULT '00000000-0000-0000-0000-000000000000' REFERENCES merchants(id);
ALTER TABLE payments ADD CONSTRAINT payments_merchant_identity UNIQUE (merchant_id,id);

CREATE FUNCTION protect_payment_merchant() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.merchant_id IS DISTINCT FROM OLD.merchant_id THEN
        RAISE EXCEPTION 'Payment merchant ownership is immutable.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_merchant_immutable BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION protect_payment_merchant();

ALTER TABLE idempotency_records ADD COLUMN merchant_id UUID NOT NULL
    DEFAULT '00000000-0000-0000-0000-000000000000' REFERENCES merchants(id);
ALTER TABLE idempotency_records DROP CONSTRAINT idempotency_records_pkey;
ALTER TABLE idempotency_records ADD PRIMARY KEY (merchant_id,idempotency_key);
ALTER TABLE idempotency_records ADD CONSTRAINT idempotency_merchant_payment_fk
    FOREIGN KEY (merchant_id,payment_id) REFERENCES payments(merchant_id,id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION protect_idempotency_scope() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Accepted idempotency snapshots are immutable.' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER idempotency_scope_immutable BEFORE UPDATE OR DELETE ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION protect_idempotency_scope();
