-- Additive amount-command mode. Existing full-payment history is not rewritten or enrolled.
ALTER TABLE payments DROP CONSTRAINT payments_status_valid;
ALTER TABLE payments ADD CONSTRAINT payments_status_valid CHECK(status IN
    ('CREATED','AUTHORIZED','CAPTURED','FAILED','REFUNDED','PARTIAL_CAPTURE','PARTIAL_REFUND'));
ALTER TABLE payment_outbox DROP CONSTRAINT payment_outbox_event_type;
ALTER TABLE payment_outbox ADD CONSTRAINT payment_outbox_event_type CHECK(event_type IN
    ('PAYMENT_CREATED','PAYMENT_AUTHORIZED','PAYMENT_CAPTURED','PAYMENT_FAILED','PAYMENT_REFUNDED','PAYMENT_PARTIAL_CAPTURE','PAYMENT_PARTIAL_REFUND'));
ALTER TABLE consumed_payment_events DROP CONSTRAINT consumed_payment_events_event_type;
ALTER TABLE consumed_payment_events ADD CONSTRAINT consumed_payment_events_event_type CHECK(event_type IN
    ('PAYMENT_CREATED','PAYMENT_AUTHORIZED','PAYMENT_CAPTURED','PAYMENT_FAILED','PAYMENT_REFUNDED','PAYMENT_PARTIAL_CAPTURE','PAYMENT_PARTIAL_REFUND'));
ALTER TABLE payment_event_projections DROP CONSTRAINT payment_event_projections_status;
ALTER TABLE payment_event_projections ADD CONSTRAINT payment_event_projections_status CHECK(status IN
    ('CREATED','AUTHORIZED','CAPTURED','FAILED','REFUNDED','PARTIAL_CAPTURE','PARTIAL_REFUND'));

CREATE TABLE payment_amount_bindings (
    payment_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL CHECK(merchant_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    source_version BIGINT NOT NULL CHECK(source_version>=0), first_operation_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(merchant_id,payment_id), FOREIGN KEY(merchant_id,payment_id) REFERENCES payments(merchant_id,id)
);
CREATE TABLE payment_amount_operations (
    operation_id UUID PRIMARY KEY CHECK(operation_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    merchant_id UUID NOT NULL,payment_id UUID NOT NULL,request_id UUID NOT NULL CHECK(request_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    source_version BIGINT NOT NULL CHECK(source_version BETWEEN 0 AND 9223372036854775806),
    action VARCHAR(8) NOT NULL CHECK(action IN ('CAPTURE','REFUND')),
    amount NUMERIC(19,2) NOT NULL CHECK(amount>0),fee NUMERIC(19,2) NOT NULL CHECK(fee>=0 AND fee<=amount),
    capture_operation_id UUID REFERENCES payment_amount_operations(operation_id),
    resulting_status VARCHAR(16) NOT NULL CHECK(resulting_status IN ('PARTIAL_CAPTURE','CAPTURED','PARTIAL_REFUND','REFUNDED')),
    principal_journal_id UUID NOT NULL UNIQUE REFERENCES ledger_transactions(id) DEFERRABLE INITIALLY DEFERRED,
    fee_journal_id UUID UNIQUE REFERENCES ledger_transactions(id) DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(created_at)),
    mutation_xid VARCHAR(20) NOT NULL DEFAULT pg_current_xact_id()::text,
    UNIQUE(merchant_id,request_id),UNIQUE(payment_id,source_version),
    FOREIGN KEY(merchant_id,payment_id) REFERENCES payment_amount_bindings(merchant_id,payment_id),
    CHECK((action='CAPTURE' AND capture_operation_id IS NULL) OR (action='REFUND' AND capture_operation_id IS NOT NULL AND fee=0)),
    CHECK((fee=0 AND fee_journal_id IS NULL) OR (fee>0 AND fee_journal_id IS NOT NULL AND fee_journal_id<>principal_journal_id))
);
ALTER TABLE payment_amount_bindings ADD FOREIGN KEY(first_operation_id) REFERENCES payment_amount_operations(operation_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX payment_amount_refunds ON payment_amount_operations(capture_operation_id) WHERE capture_operation_id IS NOT NULL;

ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_account_definition;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_account_definition CHECK(
    (account_code='PROCESSOR_CLEARING' AND normal_side='DEBIT') OR
    (account_code IN ('MERCHANT_PAYABLE','PLATFORM_FEE_REVENUE') AND normal_side='CREDIT'));
ALTER TABLE ledger_transactions ALTER COLUMN operation TYPE VARCHAR(16);
ALTER TABLE ledger_transactions ADD COLUMN amount_operation_id UUID REFERENCES payment_amount_operations(operation_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_payment_operation_unique;
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_reverses_transaction_id_key;
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transaction_operation;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transaction_operation CHECK(
    (amount_operation_id IS NULL AND ((operation='CAPTURE' AND reverses_transaction_id IS NULL) OR (operation='REFUND' AND reverses_transaction_id IS NOT NULL))) OR
    (amount_operation_id IS NOT NULL AND source='LIVE' AND ((operation IN ('AMOUNT_CAPTURE','FEE') AND reverses_transaction_id IS NULL)
        OR (operation='AMOUNT_REFUND' AND reverses_transaction_id IS NOT NULL))));
CREATE UNIQUE INDEX ledger_legacy_payment_operation ON ledger_transactions(payment_id,operation) WHERE amount_operation_id IS NULL;
CREATE UNIQUE INDEX ledger_legacy_reversal ON ledger_transactions(reverses_transaction_id) WHERE operation='REFUND';
CREATE UNIQUE INDEX ledger_amount_operation ON ledger_transactions(amount_operation_id,operation) WHERE amount_operation_id IS NOT NULL;

CREATE FUNCTION amount_payment_status(authorized NUMERIC,captured NUMERIC,refunded NUMERIC) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN refunded>0 THEN CASE WHEN refunded=captured THEN 'REFUNDED' ELSE 'PARTIAL_REFUND' END
        WHEN captured=authorized THEN 'CAPTURED' ELSE 'PARTIAL_CAPTURE' END
$$;

CREATE FUNCTION protect_amount_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p payments;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Amount bindings are immutable.' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT p FROM payments WHERE id=NEW.payment_id FOR UPDATE NOWAIT;
    IF p.merchant_id<>NEW.merchant_id OR p.status<>'AUTHORIZED' OR p.version<>NEW.source_version
        OR EXISTS(SELECT 1 FROM payment_provider_bindings WHERE payment_id=p.id)
        OR EXISTS(SELECT 1 FROM ledger_transactions WHERE payment_id=p.id) THEN
        RAISE EXCEPTION 'Amount mode requires a manually authorized, uncaptured payment.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER amount_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_amount_bindings FOR EACH ROW EXECUTE FUNCTION protect_amount_binding();

CREATE FUNCTION protect_amount_operation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p payments; c NUMERIC; r NUMERIC; original payment_amount_operations; used NUMERIC; total BIGINT;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Amount commands are immutable.' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT p FROM payments WHERE id=NEW.payment_id FOR UPDATE NOWAIT;
    SELECT COALESCE(sum(amount) FILTER(WHERE action='CAPTURE'),0),COALESCE(sum(amount) FILTER(WHERE action='REFUND'),0),count(*)
        INTO c,r,total FROM payment_amount_operations WHERE payment_id=p.id;
    IF p.merchant_id<>NEW.merchant_id OR NEW.source_version<>p.version OR total>=100
        OR NEW.mutation_xid<>pg_current_xact_id()::text OR EXISTS(SELECT 1 FROM payment_provider_bindings WHERE payment_id=p.id) THEN
        RAISE EXCEPTION 'Amount command scope/version/budget is invalid.' USING ERRCODE='23514'; END IF;
    IF NEW.action='CAPTURE' THEN
        IF p.status NOT IN ('AUTHORIZED','PARTIAL_CAPTURE') OR r<>0 OR c+NEW.amount>p.amount THEN
            RAISE EXCEPTION 'Capture exceeds authorization or capture is closed.' USING ERRCODE='23514'; END IF;
        c:=c+NEW.amount;
    ELSE
        SELECT * INTO original FROM payment_amount_operations WHERE operation_id=NEW.capture_operation_id;
        SELECT COALESCE(sum(amount),0) INTO used FROM payment_amount_operations WHERE capture_operation_id=NEW.capture_operation_id;
        IF original.operation_id IS NULL OR original.action<>'CAPTURE' OR original.payment_id<>p.id OR original.merchant_id<>NEW.merchant_id
            OR p.status NOT IN ('PARTIAL_CAPTURE','CAPTURED','PARTIAL_REFUND') OR used+NEW.amount>original.amount THEN
            RAISE EXCEPTION 'Refund must fit the named capture remaining principal.' USING ERRCODE='23514'; END IF;
        r:=r+NEW.amount;
    END IF;
    IF NEW.resulting_status<>amount_payment_status(p.amount,c,r) THEN
        RAISE EXCEPTION 'Amount command result status differs from its totals.' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER amount_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_amount_operations FOR EACH ROW EXECUTE FUNCTION protect_amount_operation();

CREATE FUNCTION guard_amount_payment() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM payment_amount_bindings WHERE payment_id=OLD.id) THEN
        IF ROW(NEW.id,NEW.merchant_id,NEW.merchant_reference,NEW.amount,NEW.currency,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.merchant_id,OLD.merchant_reference,OLD.amount,OLD.currency,OLD.created_at)
            OR NEW.version<>OLD.version+1 OR NOT EXISTS(SELECT 1 FROM payment_amount_operations a WHERE a.payment_id=OLD.id
                AND a.source_version=OLD.version AND a.resulting_status=NEW.status AND a.mutation_xid=pg_current_xact_id()::text) THEN
            RAISE EXCEPTION 'Amount-managed payments require an accepted amount command.' USING ERRCODE='P2001'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER amount_payment_guard BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION guard_amount_payment();

ALTER FUNCTION validate_ledger_transaction(UUID) RENAME TO validate_legacy_ledger_transaction;
CREATE FUNCTION validate_ledger_transaction(p_transaction_id UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE t ledger_transactions; a payment_amount_operations; p payments; original payment_amount_operations;
    n BIGINT; matching BIGINT; debit_account TEXT; credit_account TEXT; expected NUMERIC;
BEGIN
    SELECT * INTO STRICT t FROM ledger_transactions WHERE id=p_transaction_id;
    IF t.amount_operation_id IS NULL THEN
        IF EXISTS(SELECT 1 FROM payment_amount_bindings WHERE payment_id=t.payment_id) THEN
            RAISE EXCEPTION 'Legacy journals cannot mix with amount commands.' USING ERRCODE='23514'; END IF;
        PERFORM validate_legacy_ledger_transaction(p_transaction_id); RETURN;
    END IF;
    SELECT * INTO STRICT a FROM payment_amount_operations WHERE operation_id=t.amount_operation_id;
    SELECT * INTO STRICT p FROM payments WHERE id=a.payment_id;
    expected:=CASE WHEN t.operation='FEE' THEN a.fee ELSE a.amount END;
    debit_account:=CASE WHEN t.operation='AMOUNT_CAPTURE' THEN 'PROCESSOR_CLEARING' ELSE 'MERCHANT_PAYABLE' END;
    credit_account:=CASE t.operation WHEN 'AMOUNT_CAPTURE' THEN 'MERCHANT_PAYABLE' WHEN 'AMOUNT_REFUND' THEN 'PROCESSOR_CLEARING' ELSE 'PLATFORM_FEE_REVENUE' END;
    IF t.payment_id<>a.payment_id OR t.currency<>p.currency OR t.amount<>expected OR expected<=0 OR t.source<>'LIVE'
        OR (t.operation='FEE' AND (a.fee_journal_id IS DISTINCT FROM t.id OR a.action<>'CAPTURE'))
        OR (t.operation<>'FEE' AND (a.principal_journal_id<>t.id OR t.operation<>CASE a.action WHEN 'CAPTURE' THEN 'AMOUNT_CAPTURE' ELSE 'AMOUNT_REFUND' END)) THEN
        RAISE EXCEPTION 'Amount journal must match its command.' USING ERRCODE='23514'; END IF;
    IF t.operation='AMOUNT_REFUND' THEN
        SELECT * INTO STRICT original FROM payment_amount_operations WHERE operation_id=a.capture_operation_id;
        IF t.reverses_transaction_id IS DISTINCT FROM original.principal_journal_id THEN
            RAISE EXCEPTION 'Refund journal must reference its capture principal journal.' USING ERRCODE='23514'; END IF;
    END IF;
    SELECT count(*),count(*) FILTER(WHERE currency=t.currency AND amount=expected AND
        ((side='DEBIT' AND account_code=debit_account) OR (side='CREDIT' AND account_code=credit_account))) INTO n,matching
        FROM ledger_entries WHERE transaction_id=t.id;
    IF n<>2 OR matching<>2 THEN RAISE EXCEPTION 'Amount journal requires two exact balanced entries.' USING ERRCODE='23514'; END IF;
END $$;

CREATE OR REPLACE FUNCTION check_payment_ledger_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p payments; binding payment_amount_bindings; c NUMERIC; r NUMERIC; n BIGINT; captures BIGINT; refunds BIGINT; journal UUID;
BEGIN
    SELECT * INTO p FROM payments WHERE id=NEW.id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    SELECT * INTO binding FROM payment_amount_bindings WHERE payment_id=p.id;
    IF FOUND THEN
        SELECT COALESCE(sum(amount) FILTER(WHERE action='CAPTURE'),0),COALESCE(sum(amount) FILTER(WHERE action='REFUND'),0),count(*)
            INTO c,r,n FROM payment_amount_operations WHERE payment_id=p.id;
        IF c<=0 OR c>p.amount OR r>c OR n<1 OR n>100 OR p.version<>binding.source_version+n
            OR p.status<>amount_payment_status(p.amount,c,r) THEN
            RAISE EXCEPTION 'Amount payment state/version must equal complete command totals.' USING ERRCODE='23514'; END IF;
    ELSE
        SELECT count(*) FILTER(WHERE operation='CAPTURE'),count(*) FILTER(WHERE operation='REFUND') INTO captures,refunds FROM ledger_transactions WHERE payment_id=p.id;
        IF (p.status='CAPTURED' AND (captures<>1 OR refunds<>0)) OR (p.status='REFUNDED' AND (captures<>1 OR refunds<>1))
            OR (p.status NOT IN ('CAPTURED','REFUNDED') AND (captures<>0 OR refunds<>0)) OR p.status IN ('PARTIAL_CAPTURE','PARTIAL_REFUND') THEN
            RAISE EXCEPTION 'Payment final state requires complete capture/refund history.' USING ERRCODE='23514'; END IF;
    END IF;
    FOR journal IN SELECT id FROM ledger_transactions WHERE payment_id=p.id LOOP PERFORM validate_ledger_transaction(journal); END LOOP;
    RETURN NEW;
END $$;

CREATE FUNCTION check_amount_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE a payment_amount_operations; binding payment_amount_bindings; p payments; c NUMERIC; r NUMERIC; n BIGINT;
BEGIN
    IF TG_TABLE_NAME='payment_amount_bindings' THEN
        SELECT * INTO a FROM payment_amount_operations WHERE operation_id=NEW.first_operation_id;
        IF a.operation_id IS NULL OR a.payment_id<>NEW.payment_id OR a.merchant_id<>NEW.merchant_id OR a.action<>'CAPTURE' OR a.source_version<>NEW.source_version THEN
            RAISE EXCEPTION 'Amount enrollment requires its first capture command.' USING ERRCODE='23514'; END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO STRICT a FROM payment_amount_operations WHERE operation_id=NEW.operation_id;
    SELECT * INTO STRICT p FROM payments WHERE id=a.payment_id;
    SELECT * INTO STRICT binding FROM payment_amount_bindings WHERE payment_id=p.id;
    SELECT COALESCE(sum(amount) FILTER(WHERE action='CAPTURE'),0),COALESCE(sum(amount) FILTER(WHERE action='REFUND'),0),count(*)
        INTO c,r,n FROM payment_amount_operations WHERE payment_id=p.id;
    IF p.version<>binding.source_version+n OR p.status<>amount_payment_status(p.amount,c,r) THEN
        RAISE EXCEPTION 'Amount command requires its committed payment version and state.' USING ERRCODE='23514'; END IF;
    IF a.mutation_xid<>pg_current_xact_id()::text OR NOT EXISTS(SELECT 1 FROM payment_outbox e WHERE e.payment_id=a.payment_id
            AND e.payment_version=a.source_version+1 AND e.event_type='PAYMENT_'||a.resulting_status)
        OR NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE t.id=a.principal_journal_id AND t.amount_operation_id=a.operation_id)
        OR (a.fee>0 AND NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE t.id=a.fee_journal_id AND t.amount_operation_id=a.operation_id AND t.operation='FEE')) THEN
        RAISE EXCEPTION 'Amount command, journals and outbox must commit together.' USING ERRCODE='23514'; END IF;
    PERFORM validate_ledger_transaction(a.principal_journal_id);
    IF a.fee>0 THEN PERFORM validate_ledger_transaction(a.fee_journal_id); END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER amount_binding_commit AFTER INSERT ON payment_amount_bindings DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_amount_commit();
CREATE CONSTRAINT TRIGGER amount_operation_commit AFTER INSERT ON payment_amount_operations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_amount_commit();
