-- External observations are not financial posting commands or bank transfer evidence.
CREATE TABLE settlement_statements (
    statement_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    provider_instance_id UUID NOT NULL CHECK(provider_instance_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    currency VARCHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    through_sequence BIGINT NOT NULL CHECK(through_sequence BETWEEN 0 AND 10000),
    line_count INTEGER NOT NULL CHECK(line_count BETWEEN 0 AND 1000),
    captured NUMERIC(23,2) NOT NULL CHECK(captured>=0),
    refunded NUMERIC(23,2) NOT NULL CHECK(refunded>=0),
    fees NUMERIC(23,2) NOT NULL CHECK(fees>=0),
    net NUMERIC(23,2) NOT NULL CHECK(net=captured-refunded-fees),
    body TEXT NOT NULL CHECK(octet_length(body) BETWEEN 1 AND 1048576),
    body_sha256 VARCHAR(64) NOT NULL CHECK(body_sha256=encode(sha256(convert_to(body,'UTF8')),'hex')),
    key_id VARCHAR(64) NOT NULL CHECK(key_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    signature VARCHAR(67) NOT NULL CHECK(signature ~ '^v1=[0-9a-f]{64}$'),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(imported_at)),
    UNIQUE(merchant_id,statement_id),
    UNIQUE(merchant_id,provider_instance_id,currency,through_sequence),
    CHECK ((body::jsonb->>'version'='1' AND body::jsonb->>'statementId'=statement_id::text
        AND body::jsonb->>'instanceId'=provider_instance_id::text AND body::jsonb->>'merchantId'=merchant_id::text
        AND body::jsonb->>'currency'=currency AND (body::jsonb->>'throughSequence')::bigint=through_sequence
        AND jsonb_array_length(body::jsonb->'lines')=line_count AND (body::jsonb->>'captured')::numeric=captured
        AND (body::jsonb->>'refunded')::numeric=refunded AND (body::jsonb->>'fees')::numeric=fees
        AND (body::jsonb->>'net')::numeric=net) IS TRUE)
);
CREATE INDEX settlement_owned_page ON settlement_statements(merchant_id,statement_id);

CREATE TABLE settlement_reports (
    merchant_id UUID NOT NULL,
    report_id UUID NOT NULL CHECK(report_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    statement_id UUID NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL CHECK(isfinite(observed_at)),
    local_fingerprint VARCHAR(64) NOT NULL CHECK(local_fingerprint ~ '^[0-9a-f]{64}$'),
    matched BOOLEAN NOT NULL,
    discrepancy_count INTEGER NOT NULL CHECK(discrepancy_count BETWEEN 0 AND 2000),
    report JSONB NOT NULL CHECK(octet_length(report::text)<=2097152),
    PRIMARY KEY(merchant_id,report_id),
    UNIQUE(merchant_id,statement_id,report_id),
    FOREIGN KEY(merchant_id,statement_id) REFERENCES settlement_statements(merchant_id,statement_id),
    CHECK(matched=(discrepancy_count=0)),
    CHECK ((report->>'reportId'=report_id::text AND report->>'statementId'=statement_id::text
        AND (report->>'observedAt')::timestamptz=observed_at
        AND report->>'localFingerprint'=local_fingerprint
        AND (report->'comparison'->>'matched')::boolean=matched
        AND (report->'comparison'->>'discrepancyCount')::integer=discrepancy_count
        AND jsonb_array_length(report->'comparison'->'items') BETWEEN 0 AND 2000) IS TRUE)
);
CREATE INDEX settlement_report_page ON settlement_reports(merchant_id,statement_id,report_id);
CREATE FUNCTION check_settlement_report() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.discrepancy_count<>(SELECT count(*) FROM jsonb_array_elements(NEW.report->'comparison'->'items') item
        WHERE jsonb_array_length(item->'issues')>0) THEN
        RAISE EXCEPTION 'Settlement report issue count differs.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER settlement_report_evidence BEFORE INSERT ON settlement_reports FOR EACH ROW EXECUTE FUNCTION check_settlement_report();

CREATE TABLE settlement_resolutions (
    merchant_id UUID NOT NULL,
    request_id UUID NOT NULL CHECK(request_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    statement_id UUID NOT NULL,
    discrepant_report_id UUID NOT NULL,
    matched_report_id UUID NOT NULL CHECK(matched_report_id<>discrepant_report_id),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(observed_at)),
    actor VARCHAR(100) NOT NULL CHECK(length(actor) BETWEEN 1 AND 100 AND actor !~ '[[:cntrl:]]'),
    reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 300 AND reason !~ '[[:cntrl:]]'),
    local_fingerprint VARCHAR(64) NOT NULL CHECK(local_fingerprint ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(merchant_id,request_id),
    UNIQUE(merchant_id,discrepant_report_id),
    FOREIGN KEY(merchant_id,statement_id,discrepant_report_id) REFERENCES settlement_reports(merchant_id,statement_id,report_id),
    FOREIGN KEY(merchant_id,statement_id,matched_report_id) REFERENCES settlement_reports(merchant_id,statement_id,report_id)
);
CREATE FUNCTION protect_settlement_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Settlement evidence is immutable.' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_settlement_statement BEFORE UPDATE OR DELETE ON settlement_statements FOR EACH ROW EXECUTE FUNCTION protect_settlement_evidence();
CREATE TRIGGER immutable_settlement_report BEFORE UPDATE OR DELETE ON settlement_reports FOR EACH ROW EXECUTE FUNCTION protect_settlement_evidence();
CREATE TRIGGER immutable_settlement_resolution BEFORE UPDATE OR DELETE ON settlement_resolutions FOR EACH ROW EXECUTE FUNCTION protect_settlement_evidence();
CREATE FUNCTION check_settlement_resolution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM settlement_reports WHERE merchant_id=NEW.merchant_id AND report_id=NEW.discrepant_report_id AND NOT matched)
        OR NOT EXISTS(SELECT 1 FROM settlement_reports WHERE merchant_id=NEW.merchant_id AND report_id=NEW.matched_report_id
            AND matched AND local_fingerprint=NEW.local_fingerprint) THEN
        RAISE EXCEPTION 'Resolution requires discrepant and matching evidence.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER settlement_resolution_evidence BEFORE INSERT ON settlement_resolutions FOR EACH ROW EXECUTE FUNCTION check_settlement_resolution();
