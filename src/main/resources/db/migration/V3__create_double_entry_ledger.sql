CREATE TABLE ledger_accounts (
    account_code VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    normal_side VARCHAR(6) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_code, currency),
    CONSTRAINT ledger_account_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ledger_account_definition CHECK (
        (account_code = 'PROCESSOR_CLEARING' AND normal_side = 'DEBIT') OR
        (account_code = 'MERCHANT_PAYABLE' AND normal_side = 'CREDIT')
    )
);

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    operation VARCHAR(8) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    reverses_transaction_id UUID UNIQUE REFERENCES ledger_transactions (id),
    source VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_payment_operation_unique UNIQUE (payment_id, operation),
    CONSTRAINT ledger_transaction_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ledger_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_transaction_source CHECK (source IN ('LIVE', 'LEGACY_BACKFILL')),
    CONSTRAINT ledger_transaction_operation CHECK (
        (operation = 'CAPTURE' AND reverses_transaction_id IS NULL) OR
        (operation = 'REFUND' AND reverses_transaction_id IS NOT NULL)
    ),
    CONSTRAINT ledger_transaction_not_self_reversal CHECK (reverses_transaction_id IS DISTINCT FROM id)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions (id),
    account_code VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    side VARCHAR(6) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    CONSTRAINT ledger_entry_account_fk FOREIGN KEY (account_code, currency)
        REFERENCES ledger_accounts (account_code, currency),
    CONSTRAINT ledger_entry_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_entry_transaction_side_unique UNIQUE (transaction_id, side)
);

-- The transaction/side uniqueness index supports journal-entry lookup. This index supports
-- per-account, per-currency aggregate balances and account foreign-key checks.
CREATE INDEX idx_ledger_entries_account_currency ON ledger_entries (account_code, currency);

-- Existing state establishes only that full capture/refund occurred; it cannot establish
-- historical capture times. Record inferred history explicitly at migration time instead.
INSERT INTO ledger_accounts (account_code, currency, normal_side)
SELECT DISTINCT 'PROCESSOR_CLEARING', currency, 'DEBIT'
FROM payments WHERE status IN ('CAPTURED', 'REFUNDED');

INSERT INTO ledger_accounts (account_code, currency, normal_side)
SELECT DISTINCT 'MERCHANT_PAYABLE', currency, 'CREDIT'
FROM payments WHERE status IN ('CAPTURED', 'REFUNDED');

INSERT INTO ledger_transactions
    (id, payment_id, operation, currency, amount, reverses_transaction_id, source, created_at)
SELECT gen_random_uuid(), id, 'CAPTURE', currency, amount, NULL, 'LEGACY_BACKFILL', CURRENT_TIMESTAMP
FROM payments WHERE status IN ('CAPTURED', 'REFUNDED');

INSERT INTO ledger_transactions
    (id, payment_id, operation, currency, amount, reverses_transaction_id, source, created_at)
SELECT gen_random_uuid(), payment.id, 'REFUND', payment.currency, payment.amount,
       capture.id, 'LEGACY_BACKFILL', CURRENT_TIMESTAMP
FROM payments payment
JOIN ledger_transactions capture ON capture.payment_id = payment.id AND capture.operation = 'CAPTURE'
WHERE payment.status = 'REFUNDED';

INSERT INTO ledger_entries (id, transaction_id, account_code, currency, side, amount)
SELECT gen_random_uuid(), id,
       CASE WHEN operation = 'CAPTURE' THEN 'PROCESSOR_CLEARING' ELSE 'MERCHANT_PAYABLE' END,
       currency, 'DEBIT', amount
FROM ledger_transactions;

INSERT INTO ledger_entries (id, transaction_id, account_code, currency, side, amount)
SELECT gen_random_uuid(), id,
       CASE WHEN operation = 'CAPTURE' THEN 'MERCHANT_PAYABLE' ELSE 'PROCESSOR_CLEARING' END,
       currency, 'CREDIT', amount
FROM ledger_transactions;

CREATE FUNCTION validate_ledger_transaction(p_transaction_id UUID) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    v_transaction ledger_transactions%ROWTYPE;
    v_payment payments%ROWTYPE;
    v_capture ledger_transactions%ROWTYPE;
    v_entry_count BIGINT;
    v_matching_count BIGINT;
    v_debits NUMERIC;
    v_credits NUMERIC;
BEGIN
    SELECT * INTO STRICT v_transaction FROM ledger_transactions WHERE id = p_transaction_id;
    SELECT * INTO STRICT v_payment FROM payments WHERE id = v_transaction.payment_id;

    IF v_transaction.amount IS DISTINCT FROM v_payment.amount
       OR v_transaction.currency IS DISTINCT FROM v_payment.currency
       OR (v_transaction.operation = 'CAPTURE' AND v_payment.status NOT IN ('CAPTURED', 'REFUNDED'))
       OR (v_transaction.operation = 'REFUND' AND v_payment.status <> 'REFUNDED') THEN
        RAISE EXCEPTION 'Ledger transaction must match its payment amount, currency and final state.'
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*),
           COUNT(*) FILTER (WHERE entry.currency = v_transaction.currency
               AND entry.amount = v_transaction.amount
               AND (
                   (v_transaction.operation = 'CAPTURE' AND (
                       (entry.side = 'DEBIT' AND entry.account_code = 'PROCESSOR_CLEARING') OR
                       (entry.side = 'CREDIT' AND entry.account_code = 'MERCHANT_PAYABLE')
                   )) OR
                   (v_transaction.operation = 'REFUND' AND (
                       (entry.side = 'DEBIT' AND entry.account_code = 'MERCHANT_PAYABLE') OR
                       (entry.side = 'CREDIT' AND entry.account_code = 'PROCESSOR_CLEARING')
                   ))
               )),
           COALESCE(SUM(entry.amount) FILTER (WHERE entry.side = 'DEBIT'), 0),
           COALESCE(SUM(entry.amount) FILTER (WHERE entry.side = 'CREDIT'), 0)
    INTO v_entry_count, v_matching_count, v_debits, v_credits
    FROM ledger_entries entry WHERE entry.transaction_id = v_transaction.id;

    IF v_entry_count <> 2 OR v_matching_count <> 2
       OR v_debits IS DISTINCT FROM v_transaction.amount
       OR v_credits IS DISTINCT FROM v_transaction.amount THEN
        RAISE EXCEPTION 'Ledger transaction requires two matching balanced debit and credit entries.'
            USING ERRCODE = '23514';
    END IF;

    IF v_transaction.operation = 'REFUND' THEN
        SELECT * INTO v_capture FROM ledger_transactions WHERE id = v_transaction.reverses_transaction_id;
        IF NOT FOUND OR v_capture.operation <> 'CAPTURE'
           OR v_capture.payment_id IS DISTINCT FROM v_transaction.payment_id
           OR v_capture.currency IS DISTINCT FROM v_transaction.currency
           OR v_capture.amount IS DISTINCT FROM v_transaction.amount THEN
            RAISE EXCEPTION 'Refund must reverse the same payment capture in full and in the same currency.'
                USING ERRCODE = '23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION check_ledger_transaction_insert() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'ledger_transactions' THEN
        PERFORM validate_ledger_transaction(NEW.id);
    ELSE
        PERFORM validate_ledger_transaction(NEW.transaction_id);
    END IF;
    RETURN NEW;
END;
$$;

-- Header validation catches empty journals. Entry validation also prevents adding legs later.
CREATE CONSTRAINT TRIGGER ledger_transaction_balanced
AFTER INSERT ON ledger_transactions DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_ledger_transaction_insert();

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_ledger_transaction_insert();

CREATE FUNCTION check_payment_ledger_consistency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_payment payments%ROWTYPE;
    v_capture_count BIGINT;
    v_refund_count BIGINT;
    v_transaction_id UUID;
BEGIN
    -- Query final state rather than queued NEW: multiple lifecycle transitions can share a transaction.
    SELECT * INTO v_payment FROM payments WHERE id = NEW.id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    SELECT COUNT(*) FILTER (WHERE operation = 'CAPTURE'), COUNT(*) FILTER (WHERE operation = 'REFUND')
    INTO v_capture_count, v_refund_count
    FROM ledger_transactions WHERE payment_id = v_payment.id;

    IF (v_payment.status = 'CAPTURED' AND (v_capture_count <> 1 OR v_refund_count <> 0))
       OR (v_payment.status = 'REFUNDED' AND (v_capture_count <> 1 OR v_refund_count <> 1))
       OR (v_payment.status NOT IN ('CAPTURED', 'REFUNDED')
           AND (v_capture_count <> 0 OR v_refund_count <> 0)) THEN
        RAISE EXCEPTION 'Payment final state requires its complete capture/refund ledger history.'
            USING ERRCODE = '23514';
    END IF;

    -- Also guards raw SQL changes to payment amount/currency after a journal has been recorded.
    FOR v_transaction_id IN SELECT id FROM ledger_transactions WHERE payment_id = v_payment.id LOOP
        PERFORM validate_ledger_transaction(v_transaction_id);
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER payment_ledger_consistent
AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_payment_ledger_consistency();

CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger history is append-only; use a new reversal transaction instead.'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER ledger_accounts_immutable BEFORE UPDATE OR DELETE ON ledger_accounts
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_transactions_immutable BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- Validate inferred journals as well; triggers were attached after the backfill inserts.
DO $$
DECLARE
    v_transaction_id UUID;
BEGIN
    FOR v_transaction_id IN SELECT id FROM ledger_transactions LOOP
        PERFORM validate_ledger_transaction(v_transaction_id);
    END LOOP;
END;
$$;
