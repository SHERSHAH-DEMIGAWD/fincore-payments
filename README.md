# fincore-payments

[![Container validation](https://github.com/SHERSHAH-DEMIGAWD/fincore-payments/actions/workflows/fincore-payments.yml/badge.svg)](https://github.com/SHERSHAH-DEMIGAWD/fincore-payments/actions/workflows/fincore-payments.yml)

An educational payment-processing backend with a double-entry ledger, durable events, merchant isolation, sandbox provider integration, reconciliation, risk controls, observability and recovery tooling. All 25 planned functionality milestones are implemented. It operates with sandbox funds.

**Validation:** the last completed local application run recorded 1,269 passes and 454 Docker-dependent skips. Full container/Redis validation remains pending until the [GitHub Actions workflow](https://github.com/SHERSHAH-DEMIGAWD/fincore-payments/actions/workflows/fincore-payments.yml) establishes a passing result. See [validation scope and evidence](docs/VALIDATION_STATUS.md). This is a learning project; production readiness is not certified.

```sh
git clone https://github.com/SHERSHAH-DEMIGAWD/fincore-payments.git
cd fincore-payments
```

An independent Java backend for learning payment infrastructure. The long-term goal is a Distributed Payment Processing Platform; this project implements **Milestone 1 — Payment Service Foundation**, **Milestone 2 — Idempotent Payment Creation**, **Milestone 3 — Double-Entry Ledger**, **Milestone 4 — Transactional Outbox and Payment Events**, **Milestone 5 — Kafka Delivery and Idempotent Consumer Projection**, **Milestone 6 — Event Operations and Audited Outbox Recovery**, **Milestone 7 — Controlled Consumer Quarantine and Original Replay**, **Milestone 8 — Read-Only Projection Reconciliation and Coverage Assessment**, **Milestone 9 — Isolated Shadow Projection Rebuild and Catch-Up Generations**, **Milestone 10 — Authenticated Projection Promotion and Rollback**, **Milestone 11 — Controlled Projection Target Replacement and Retirement**, **Milestone 12 — Retained Evidence Review and Operating Limits**, **Milestone 13 — Audited Bounded Purge Planning**, **Milestone 14 — Verified Preservation Exports and Authenticated Review Approval**, **Milestone 15 — Approval Lifecycle and Independent File Verification**, **Milestone 16 — Merchant Authentication and Tenant Isolation**, **Milestone 17 — Sandbox Payment-Provider Integration**, **Milestone 18 — Verified Incoming Provider Webhooks**, **Milestone 19 — Reliable Outgoing Merchant Webhooks**, **Milestone 20 — Partial Payment Operations and Explicit Fees**, **Milestone 21 — Independent Settlement Statements and Reconciliation**, **Milestone 22 — Redis Admission Controls and Risk Rules**, **Milestone 23 — Payment Observability**, **Milestone 24 — Database Recovery and Operational Closure**, and **Milestone25 — Reproducible Deployment and Final Validation**.

**Start learning here:** [Complete learning guide — Milestones 1–10](docs/COMPLETE_LEARNING_GUIDE.md), then the [Milestone 11 continuation](docs/MILESTONE_11_LEARNING_GUIDE.md), [Milestone 12 continuation](docs/MILESTONE_12_LEARNING_GUIDE.md), [Milestone 13 continuation](docs/MILESTONE_13_LEARNING_GUIDE.md), [Milestone 14 continuation](docs/MILESTONE_14_LEARNING_GUIDE.md), [Milestone 15 continuation](docs/MILESTONE_15_LEARNING_GUIDE.md), [Milestone 16 continuation](docs/MILESTONE_16_LEARNING_GUIDE.md), [Milestone 17 continuation](docs/MILESTONE_17_LEARNING_GUIDE.md), [Milestone 18 continuation](docs/MILESTONE_18_LEARNING_GUIDE.md), [Milestone 19 continuation](docs/MILESTONE_19_LEARNING_GUIDE.md), and the [Milestone 20 continuation](docs/MILESTONE_20_LEARNING_GUIDE.md). They explain architecture, concepts, implementation choices and alternatives, failure scenarios, practical impact and proposed next work.

**Consolidated continuation:** [Complete learning guide — Milestones 11–20](docs/COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.md). It connects all ten milestones with architecture, worked scenarios, concept explanations, code flows, alternatives and costs, recovery, dated test evidence, current status, the proposed M21 block and a teacher handoff.

**Word editions:** [Milestones 1 through 10](docs/FINCORE_PAYMENTS_COMPLETE_LEARNING_GUIDE_MILESTONES_1_TO_10.docx) · [Milestones 11 through 20](docs/FINCORE_PAYMENTS_COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.docx). Both use the same teaching format. The first remains its original historical snapshot; the continuation records the implementation through Milestone 20.

**Final five milestones report:** [Word report for Milestones 21–25](docs/FINCORE_PAYMENTS_COMPLETE_LEARNING_GUIDE_MILESTONES_21_TO_25.docx) · [Searchable Markdown companion](docs/COMPLETE_LEARNING_GUIDE_MILESTONES_21_TO_25.md). Covers settlement reconciliation, admission/risk, observability, logical recovery and release/acceptance, with worked flows, alternatives, dated evidence and the next validation action.

**Latest milestone:** [Milestone25 — deployment and final validation](docs/MILESTONE_25_LEARNING_GUIDE.md), with a [deployment runbook](docs/DEPLOYMENT_RUNBOOK.md), [complete project map](docs/FINAL_PROJECT_GUIDE.md), [final validation report](docs/FINAL_VALIDATION_REPORT.md) and [concise summary](docs/MILESTONE_25_SUMMARY.md). It follows [M24 logical recovery](docs/MILESTONE_24_LEARNING_GUIDE.md). The Word volumes remain historical M1–10/M11–20 snapshots.

**Completion roadmap:** [Milestones16–25 and finish criteria](docs/ROADMAP.md). All25 planned functionality blocks are implemented. Zero planned implementation blocks remain. Docker/real Redis combined execution and other environment/proof limits are tracked separately; the next action is validation on a Docker-enabled machine. This is a learning-project finish, not a production-readiness claim.

## Stack and scope

Java 21, Spring Boot 3.5, Maven, PostgreSQL, Spring Data JPA/Hibernate, Redis/Lettuce/Lua, Flyway, Spring for Apache Kafka, Spring Security, Actuator, Micrometer Observation, OpenTelemetry/OTLP, Prometheus, Bean Validation, JUnit 5, Mockito, Testcontainers, and Docker Compose.

Create and retrieve payments, authorize, capture, fail, and refund them. Domain methods enforce the lifecycle, service transactions persist operations atomically, and optimistic locking detects concurrent updates. Captures and full refunds post balanced, immutable ledger transactions. Each new payment or valid transition also saves a durable payment event in that same transaction. An optional background dispatcher delivers events to the default local PostgreSQL inbox or, when selected, Kafka. A Kafka consumer builds a durable payment projection without changing payments or ledger entries.

### Verified local sandbox release

`scripts/Build-LocalRelease.ps1` runs Java21 clean verification and creates a secret-free ZIP with the exact tested JAR, manifest, checksums, deployment recipe and operating guides. `init-local-sandbox` creates dedicated random inputs once; `bootstrap-local-sandbox` migrates a dedicated demo database and atomically provisions two merchants without rotating existing credentials. A separate digest-pinned `deployment/compose.yml` runs API, provider, PostgreSQL, Kafka and Redis with one published loopback port. `sandbox-acceptance` exercises actual authenticated HTTP, provider uncertainty/signed recovery, partial accounting, settlement, merchant notification retry and bounded concurrent creation. See the [runbook](docs/DEPLOYMENT_RUNBOOK.md) for exact commands, reruns, evidence and retained-volume behavior. Native packaged acceptance includes real Kafka; it explicitly reports risk disabled on this host. Docker builds/runtime and real Redis remain unexecuted here.

### Logical database recovery

The headless JAR commands `create-recovery-backup` and `verify-recovery-restore` capture a supported V22 PostgreSQL database with an exported-snapshot fingerprint, then compare a separately restored `fincore_restore_*` database against a trusted manifest hash. Dedicated `FINCORE_RECOVERY_DB_*` environment variables keep credentials separate from application startup. The commands start no Spring workers and execute no restore/deletion SQL. The [runbook](docs/DATABASE_RECOVERY_RUNBOOK.md) supplies the explicit new-database pg_restore sequence; the [guide](docs/MILESTONE_24_LEARNING_GUIDE.md) explains consistency, fingerprints and limits.

Native verification reconstructs real databases and exercises genuine HTTP historical retries, ownership, journals and partial-capture fees. This proves local supported logical reconstruction, not off-site/physical/PITR or external-resource recovery. The [retention policy](docs/RETENTION_POLICY.md) retains authoritative records and keeps deletion disabled. No new migration: schema stays V22.

### Private observability

Enable `FINCORE_OBSERVABILITY_ENABLED=true` with a separate `FINCORE_OBSERVABILITY_USERNAME` / raw BCrypt `FINCORE_OBSERVABILITY_PASSWORD_HASH`. Its isolated read-only identity can GET `/internal/observability/prometheus`, `/health`, `/health/readiness` and `/health/liveness` under that base path. Merchant/operator credentials cannot authenticate there; the observer gains no payment or recovery privileges. Health details/configuration/debug endpoints are not exposed.

Fixed flow/outcome metrics cover payment, risk, provider, delivery and recovery calls. A dedicated worker samples global source backlog counts/ages; scrapes read cached gauges with explicit freshness. Optional `FINCORE_TRACE_EXPORT_ENABLED=true` exports sampled spans to local OTLP HTTP and returns `X-Fincore-Trace-Id` correlation. See the [runbook](docs/OBSERVABILITY_RUNBOOK.md) for credentials, the separate `docker-compose.observability.yml` overlay, eleven alert rules, triage and verification limits. Schema stays V22. Telemetry is diagnostic; durable journals/audits retain financial authority.

### Partial local accounting

For a manually authorized payment before its first capture, use POST `/api/v1/payments/{paymentId}/amount-operations` with a request UUID, explicit expected version, CAPTURE/REFUND, principal, fee and the refund's capture reference. GET `/accounting` exposes current principal/fee totals and capacity; GET `/amount-operations` exposes immutable history. The first amount capture enrolls this workflow permanently; it cannot mix with full-provider or full-payment operations. See the [accounting demo](docs/PAYMENT_ACCOUNTING_RUNBOOK.md) for split captures, capture-specific refunds, retained fees and exact retries.

### Independent sandbox settlement reconciliation

The provider can export signed cumulative statements at GET `/v1/settlements/{merchantId}/{currency}` from its own decision journal. Enable its separate `FINCORE_SANDBOX_SETTLEMENT_SIGNING_KEY`; configure the API's `FINCORE_SETTLEMENT_ENABLED`, `FINCORE_SETTLEMENT_SIGNING_KEY`, `FINCORE_SETTLEMENT_KEY_ID` and pinned `FINCORE_SETTLEMENT_INSTANCE_ID`. Both features default off. See the [runbook](docs/SETTLEMENT_RECONCILIATION_RUNBOOK.md) for complete configuration and a lost-reply recovery example.

Merchant Basic/CSRF protects POST `/api/v1/merchant/settlement-statements`, POST `/{statementId}/reports` and POST `/{statementId}/reports/{reportId}/resolve`. Owned GETs expose metadata, original `/document`, reports and `/resolution`. Statements retain exact authenticated bytes; reports compare operation identities/principal/fees/confirmed LIVE journals; closure requires fresh matching evidence for the same statement. Import/report/resolve never post money. Caps are 1 MiB/1,000 statement lines, 1,000 local commands and 2 MiB/2,000 report items. This covers full-amount sandbox provider operations with zero expected fees, independently of M20 local partial accounting; it does not execute bank payouts.

### Redis admission and deterministic risk rules

Enable `FINCORE_RISK_ENABLED=true` to screen new merchant HTTP payment creations for one supported currency, maximum original principal and a Redis-backed admission quota. Defaults are INR100000.00 and 20 admitted intents per first-admission-anchored 60-second window. Redis uses an atomic bounded Lua hash/TTL reservation; PostgreSQL binds request intent and retains immutable decisions. Use `docker compose --profile risk up -d --wait postgres redis` for the optional loopback-only demo infrastructure and follow the [runbook](docs/PAYMENT_RISK_RUNBOOK.md).

Static policy denial returns422, temporary capacity exhaustion429/Retry-After, and an uncertain Redis check503. Refusals include `Fincore-Risk-Decision-Id`; owned GET `/api/v1/merchant/risk-decisions` and `/{decisionId}` expose history. Accepted creation/admission retries bypass Redis, while throttle/outage requests can retry the same bound intent. Other payment/provider/recovery operations retain their paths. Controls default off; enabling requires coordinated policy/namespace. Ephemeral Redis loss can replenish quota, so this is a soft admission control, not a hard financial exposure limit. Real Redis container/combined tests are supplied but could not run on this machine; see the dated [summary](docs/MILESTONE_22_SUMMARY.md).

## Setup

Install a Java 21 JDK and Docker with Compose. Set `JAVA_HOME` to Java 21 and check `java -version`. A Maven installation is unnecessary: the checked-in Maven wrapper downloads Maven 3.9.11 on its first run.

Run commands from this directory:

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

On Windows PowerShell use `docker compose up -d --wait` and `.\mvnw.cmd spring-boot:run`.

For this checkout, the implementation session downloaded a portable Java 21 JDK into the ignored sibling `.tools` folder. If your system still selects Java 17, set Java 21 for the current PowerShell session before using the wrapper:

```powershell
$env:JAVA_HOME = (Resolve-Path '..\.tools\jdk-21.0.12.1+1').Path
.\mvnw.cmd test
```

The API listens on `http://localhost:8080`. PostgreSQL binds to `127.0.0.1:5432`, with a named volume for data. Flyway creates the schema at application startup; Hibernate uses `ddl-auto: validate`.

Defaults (`fincore_payments`, `fincore`, `local_dev_only`) are local development values, not production secrets. Copy `.env.example` to `.env` to change Compose settings. Configure the application separately with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables matching those settings; Spring does not automatically load the Compose `.env` file. `SERVER_PORT` changes the API port. If `POSTGRES_PORT` changes, update the port in `DB_URL` too.

Stop PostgreSQL with `docker compose down`; its named volume remains available for the next startup.

Before calling the API, [provision a merchant and obtain its CSRF token/session](docs/MERCHANT_ACCESS_RUNBOOK.md). No merchant credentials are enabled by default. Existing records remain in a disabled legacy scope; newly provisioned accounts do not acquire them.

## API

| Method | Endpoint | Successful result |
| --- | --- | --- |
| GET | `/api/v1/merchant/me` | Safe authenticated merchant identity |
| GET | `/api/v1/merchant/csrf` | CSRF token/header metadata; retain its session cookie |
| GET | `/api/v1/merchant/risk-decisions` | Owned UUID-keyset decision history; limit 1–100 |
| GET | `/api/v1/merchant/risk-decisions/{decisionId}` | Owned immutable admission/policy observation |
| POST | `/api/v1/payments` | Merchant credentials + CSRF + `Idempotency-Key`; 201 on creation, 200 on replay |
| GET | `/api/v1/payments/{paymentId}` | 200, payment JSON |
| POST | `/api/v1/payments/{paymentId}/authorize` | 200, AUTHORIZED payment |
| POST | `/api/v1/payments/{paymentId}/capture` | 200, CAPTURED payment |
| POST | `/api/v1/payments/{paymentId}/fail` | 200, FAILED payment |
| POST | `/api/v1/payments/{paymentId}/refund` | 200, REFUNDED payment |
| POST | `/api/v1/payments/{paymentId}/provider-operations` | 200, saved provider command/progress; inspect operation state, not just HTTP status |
| GET | `/api/v1/payments/{paymentId}/provider-operations` | 200, owned UUID-keyset page; limit 1–100 |
| GET | `/api/v1/payments/{paymentId}/provider-operations/{operationId}` | 200, owned command progress |
| GET | `/api/v1/payments/{paymentId}/provider-operations/{operationId}/webhooks` | 200, owned receipt metadata; limit 1–100 |
| GET | `/api/v1/payments/{paymentId}/ledger` | 200, recorded transactions with their entries |
| GET | `/api/v1/ledger/accounts/{currency}/balances` | 200, this merchant's posted account totals for one currency |
| GET | `/api/v1/payments/{paymentId}/events` | 200, saved payment events with delivery metadata |
| GET | `/api/v1/payments/{paymentId}/projection` | 200, active routed consumer snapshot; 404 until projected |

Every endpoint in this table requires merchant Basic credentials. POST also requires a CSRF token and its session cookie. Foreign and missing payment UUIDs both return 404. Operator credentials do not authenticate as a merchant.

The separate machine endpoint `POST /api/v1/provider-webhooks/sandbox` requires exact-body HMAC, timestamp and key-ID headers. It has its own exact-path security chain; merchant credentials do not authenticate callbacks. See the [webhook runbook](docs/PROVIDER_WEBHOOK_RUNBOOK.md).

Bash/curl examples use a provisioned username. `--user` prompts for its original password rather than embedding it in command history. First save the CSRF cookie/token, then reuse them for the examples below:

```sh
MERCHANT_USER='your-provisioned-merchant'
curl --user "$MERCHANT_USER" -c merchant.cookies http://localhost:8080/api/v1/merchant/csrf
# Copy the returned token value; the default headerName is X-CSRF-TOKEN.
MERCHANT_CSRF_TOKEN='paste-the-returned-token'

curl --user "$MERCHANT_USER" -b merchant.cookies -H "X-CSRF-TOKEN: $MERCHANT_CSRF_TOKEN" \
  -i -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-order-123' \
  -d '{"merchantReference":"ORDER-123","amount":1499.99,"currency":"INR"}'
curl --user "$MERCHANT_USER" http://localhost:8080/api/v1/payments/PAYMENT_ID
```

PowerShell can retain the credentials and CSRF session locally:

```powershell
$apiBase = 'http://localhost:8080'
$merchantCredential = Get-Credential -Message 'Provisioned merchant and original password'
$merchantBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $merchantCredential.UserName + ':' + $merchantCredential.GetNetworkCredential().Password))
$merchantHeaders = @{ Authorization = 'Basic ' + $merchantBasic }
$merchantSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$merchantCsrf = Invoke-RestMethod -Uri "$apiBase/api/v1/merchant/csrf" -Headers $merchantHeaders -WebSession $merchantSession
$merchantHeaders[$merchantCsrf.headerName] = $merchantCsrf.token
$createHeaders = $merchantHeaders.Clone()
$createHeaders['Idempotency-Key'] = 'demo-order-123'
$payment = Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/payments" `
    -Headers $createHeaders -WebSession $merchantSession -ContentType 'application/json' `
    -Body '{"merchantReference":"ORDER-123","amount":1499.99,"currency":"INR"}'
Invoke-RestMethod -Uri "$apiBase/api/v1/payments/$($payment.id)" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/payments/$($payment.id)/authorize" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/payments/$($payment.id)/capture" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Method Post -Uri "$apiBase/api/v1/payments/$($payment.id)/refund" -Headers $merchantHeaders -WebSession $merchantSession
```

Input rules: nonblank merchant reference of at most 255 characters; positive amount with at most 17 integer digits and 2 decimal places; currency matching `[A-Z]{3}`. Money uses `BigDecimal` and PostgreSQL `NUMERIC(19,2)`, without silent rounding. Currency syntax is validated; this milestone does not verify an ISO currency registry or currency-specific decimal scales.

## Payment lifecycle

```text
CREATED
 ├── AUTHORIZED
 │      ├── CAPTURED
 │      │      └── REFUNDED
 │      └── FAILED
 └── FAILED
```

FAILED and REFUNDED are terminal. Repeating a transition is an illegal transition. The API returns 400 for invalid input, 404 for missing payments, and 409 for illegal transitions or conflicting concurrent updates.

Errors have `timestamp`, `status`, `error`, `message`, `path`, and optional `fieldErrors`. SQL errors and stack traces are never returned to clients.

Each manual payment transition is a service transaction. `@Version` prevents two transactions that read the same payment version from both committing updates. A stale update returns `CONCURRENT_PAYMENT_UPDATE` (409); fetch the latest state before deciding whether to retry. Creation retries use the idempotency mechanism below.

## Idempotent Payment Creation

If creation succeeds but the client loses the response, retry with the **same key and logical request**. Keys are opaque, case-sensitive, 1–255 visible ASCII characters (`!` through `~`), with no whitespace. Missing, blank, oversized, or invalid keys return structured 400 `INVALID_IDEMPOTENCY_KEY`. Keys are scoped to the authenticated merchant. Different merchants may use the same key independently; within one merchant use a new key for each new logical creation.

```text
POST /api/v1/payments + Idempotency-Key
  ├── new key               → create one payment → 201 + Location
  ├── same logical request  → original snapshot  → 200
  └── different request     → conflict          → 409
```

Replays return the original creation response, including its `CREATED` status and timestamps, even if the payment is now authorized/captured/refunded. Use GET to retrieve its current state. Changing merchant reference, amount, or currency while reusing the key returns `IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST` (409).

First request, then safe retry (the same command):

```sh
curl --user "$MERCHANT_USER" -b merchant.cookies -H "X-CSRF-TOKEN: $MERCHANT_CSRF_TOKEN" -i -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: retry-demo-123' \
  -d '{"merchantReference":"ORDER-123","amount":1000,"currency":"INR"}'

curl --user "$MERCHANT_USER" -b merchant.cookies -H "X-CSRF-TOKEN: $MERCHANT_CSRF_TOKEN" -i -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: retry-demo-123' \
  -d '{"merchantReference":"ORDER-123","amount":1000.00,"currency":"INR"}'
```

SHA-256 fingerprints length-prefixed UTF-8 fields: a version marker, the exact merchant reference, `amount.stripTrailingZeros().toPlainString()`, and uppercase currency using `Locale.ROOT`. Thus `1000`, `1000.0`, and `1000.00` match; JSON spacing/order does not matter. Currency validation still requires uppercase input. Existing amount precision validation rejects unsupported fractions rather than silently rounding.

`PaymentService.createPayment` uses one READ COMMITTED transaction shared by JDBC and JPA. A complete response snapshot claims the key using `INSERT … ON CONFLICT DO NOTHING`; only the caller receiving an affected-row count of 1 inserts a payment. A deferred foreign key permits claiming first and checks payment existence at commit. All fields are non-null, so no pending-record state or completion update is needed. A failure rolls back both inserts.

PostgreSQL's primary-key uniqueness arbitrates concurrent requests across instances. A competing insert waits for the owner; after commit, a separate SELECT reads the saved fingerprint/snapshot. If the owner rolls back, a contender can claim instead. No uniqueness exception is caught inside an aborted transaction. The separate SELECT relies on READ COMMITTED and on records remaining present. See [PostgreSQL isolation behavior](https://www.postgresql.org/docs/17/transaction-iso.html) and [deferred constraints](https://www.postgresql.org/docs/17/sql-createtable.html).

Records have no expiration or deletion workflow. Different keys intentionally allow separate payments for the same merchant reference. Lightweight DEBUG logs identify creation/replay/conflict without logging keys or payment payloads.

## Double-Entry Ledger

The ledger records two pooled accounts per currency. `PROCESSOR_CLEARING` is an asset account with a normal debit balance; `MERCHANT_PAYABLE` is a liability account with a normal credit balance. They represent recorded clearing amounts and obligations in this learning model. The project has no merchant identity model, so these are platform-wide accounts, not individual merchant wallets or actual bank balances.

| Payment operation | Debit | Credit |
| --- | --- | --- |
| Capture | PROCESSOR_CLEARING | MERCHANT_PAYABLE |
| Full refund | MERCHANT_PAYABLE | PROCESSOR_CLEARING |

Every transaction has exactly two positive entries for the payment's full amount and currency. Refunds reference and reverse the original capture. Creation, authorization, failure, and creation-response replays produce no ledger entries. Repeating a capture/refund still returns the existing lifecycle conflict (409); it cannot post twice.

The version-checked payment update is flushed before posting, and both operations share one service transaction. A posting failure rolls back the payment state/version together with the ledger writes. PostgreSQL also enforces unique `(payment_id, operation)` transactions, one reversal per capture, matching payment amounts/currencies, and deferred checks for complete balanced entries and the final payment/ledger state. Accounts, transactions, and entries reject ordinary UPDATE and DELETE statements; refunds add new transactions instead of editing history.

These cross-row checks use PostgreSQL [deferred constraint triggers](https://www.postgresql.org/docs/17/sql-createtrigger.html), evaluated against the complete transaction at commit.

Balances are derived from committed entries, grouped by account and currency. Responses expose `totalDebits`, `totalCredits`, `normalSide`, and `balance`: debits minus credits for clearing, credits minus debits for payable. No mutable balance counter is maintained, and values from different currencies are never summed together.

```sh
# After creating, authorizing, and capturing a payment:
curl --user "$MERCHANT_USER" http://localhost:8080/api/v1/payments/PAYMENT_ID/ledger
curl --user "$MERCHANT_USER" http://localhost:8080/api/v1/ledger/accounts/INR/balances

# Add a full reversal, then inspect both transactions:
curl --user "$MERCHANT_USER" -b merchant.cookies -H "X-CSRF-TOKEN: $MERCHANT_CSRF_TOKEN" -X POST http://localhost:8080/api/v1/payments/PAYMENT_ID/refund
curl --user "$MERCHANT_USER" http://localhost:8080/api/v1/payments/PAYMENT_ID/ledger
```

Flyway V3 backfills existing CAPTURED payments with captures and existing REFUNDED payments with captures plus reversals. These transactions use `source: LEGACY_BACKFILL` and migration-time timestamps: the earlier milestones did not record historical capture/refund times. Subsequent postings use `source: LIVE`. V1/V2 and original idempotency snapshots remain unchanged.

The read APIs expose recorded history and derived balances; there is no general-purpose posting/transfer endpoint, partial capture/refund, fee accounting, settlement, or actual money movement in this milestone.

## Transactional Outbox and Payment Events

Each new payment and valid lifecycle transition records an immutable event envelope containing an event UUID, payment ID/version, event type, schema version `1`, occurrence time, and a `PaymentResponse` data snapshot. The payment change, any ledger posting, and its outbox insert commit together. Invalid transitions and idempotent creation replays add no events. Flyway V4 does not invent historical events for earlier operations; existing payments acquire events only through subsequent valid operations.

```text
Payment operation
  → payment + optional ledger + outbox event COMMIT
  → optional dispatcher locks a due event
  → local PostgreSQL inbox receipt + DELIVERED acknowledgement COMMIT
```

The default transport remains a durable **LOCAL database inbox**, so the existing local workflow needs no Kafka broker. For that sink, inbox receipt and delivery acknowledgement share one database transaction. Milestone 5 adds opt-in Kafka delivery through the same `PaymentEventPublisher` interface. Kafka delivery is at least once: broker acknowledgement and the outbox database commit are separate operations, so a crash can publish the same event UUID again.

Workers select a due event using `FOR UPDATE SKIP LOCKED`, with one event per transaction. They preserve payment-version order by selecting an event only when every older event for that payment is DELIVERED. A failed publication rolls back the delivery transaction; a separate conditional transaction increments the expected attempt count and schedules bounded exponential backoff. After the current cycle's attempt limit the event becomes DEAD, blocking later events for that payment while other payments can continue.

`attempts` counts lifetime acknowledged successes and recorded failures; `cycle_attempts` counts the current retry cycle. Requeue resets only the cycle and increments `retry_generation`, while lifetime attempts remain monotonic for stale-failure protection. Concurrent failures or a crash before failure bookkeeping can produce extra publisher calls; `max-attempts` bounds recorded failures per cycle rather than physical invocations. Stable event IDs support repeated local receipt and Kafka consumer deduplication. PostgreSQL documents [SKIP LOCKED for queue-like workloads](https://www.postgresql.org/docs/17/sql-select.html).

There is no PROCESSING/lease state to recover: a worker crash rolls back its transaction and PostgreSQL releases its row lock. Source envelopes and inbox history are immutable; only delivery metadata changes. The operator CLI can now requeue DEAD events with immutable audit; no HTTP requeue endpoint, automatic DEAD recovery, or retention workflow exists.

The scheduler is disabled by default. Enable it in PowerShell before starting the application:

```powershell
$env:FINCORE_OUTBOX_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

| Configuration property | Default |
| --- | --- |
| `fincore.outbox.enabled` | `false` |
| `fincore.outbox.fixed-delay-ms` | `1000` |
| `fincore.outbox.batch-size` | `50` |
| `fincore.outbox.max-attempts` | `5` |
| `fincore.outbox.initial-backoff-ms` | `1000` |
| `fincore.outbox.max-backoff-ms` | `60000` |

```sh
# After creating or transitioning a payment:
curl --user "$MERCHANT_USER" http://localhost:8080/api/v1/payments/PAYMENT_ID/events
```

The read endpoint shows the durable source events and delivery metadata even when scheduling is disabled. There is no HTTP dispatch endpoint; delivery is performed by the scheduler/dispatcher.

## Kafka delivery and consumer projection

Kafka is optional. From this directory, start the Compose profile and select Kafka before starting the application:

```powershell
docker compose --profile kafka up -d --wait
$env:FINCORE_EVENT_TRANSPORT = 'kafka'
$env:FINCORE_OUTBOX_ENABLED = 'true'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
.\mvnw.cmd spring-boot:run
```

The default topic is `payment-events.v1`; the default consumer group is `fincore-payment-projection-v1`. With `FINCORE_EVENT_TRANSPORT` absent or set to `local`, Kafka components are not enabled. `FINCORE_OUTBOX_ENABLED` controls dispatch scheduling independently: selecting Kafka alone starts the consumer but does not publish pending outbox events.

Switching transports does not republish events already marked DELIVERED through the local sink. Create or transition a payment after selecting Kafka to exercise the new flow. There is no automatic historical replay or projection backfill.

| Configuration | Default |
| --- | --- |
| `FINCORE_EVENT_TRANSPORT` | `local` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `FINCORE_KAFKA_TOPIC` | `payment-events.v1` |
| `FINCORE_KAFKA_CONSUMER_GROUP` | `fincore-payment-projection-v1` |
| `FINCORE_KAFKA_SEND_TIMEOUT_MS` | `15000` (publisher acknowledgement wait) |

`KafkaEventConfiguration` creates a three-partition topic with replication factor one and three listener threads. It pins string codecs, `acks=all`, producer idempotence, one in-flight request, 5-second request/metadata-block timeouts, and a 10-second delivery timeout. The publisher's acknowledgement wait is separately bounded by the setting above. If `KAFKA_PORT` changes in Compose, change `KAFKA_BOOTSTRAP_SERVERS` to match.

```text
PaymentService operation → payment + ledger + outbox COMMIT
  → OutboxDispatcher → KafkaPaymentEventPublisher.publish → broker acknowledgement
  → outbox DELIVERED COMMIT
KafkaPaymentEventListener.onMessage → PaymentEventConsumer.consume
  → receipt + newer projection COMMIT → Kafka offset commit
GET /api/v1/payments/{paymentId}/projection
  → PaymentProjectionController.getProjection → PaymentEventConsumer.getProjection
  → PaymentEventConsumerRepository.findProjection → PaymentProjectionResponse
```

The producer sends the payment UUID as the Kafka key, with `acks=all`, producer idempotence, and bounded send timeouts. It waits for broker acknowledgement before the dispatcher marks the outbox row DELIVERED. Acknowledgement means the broker accepted the record; it does **not** mean a consumer has projected it. The dispatcher retains its database row lock during the bounded send. Broker unavailability uses the existing outbox retry/backoff/DEAD handling.

The consumer reads string keys/JSON values, validates schema version `1` and snapshot consistency, and requires the Kafka key to match the envelope payment ID. `PaymentEventConsumer.consume` uses one PostgreSQL transaction for an immutable `consumed_payment_events` receipt and a `payment_event_projections` upsert. A repeated event ID must carry the same logical envelope. Reusing a payment version with another event ID is also a conflict. An older unique event is recorded but cannot replace a newer projection. These tables do not write to the payment or ledger tables.

The listener uses record acknowledgements with auto-commit disabled. The transactional consumer service commits before the listener returns; the container then commits the offset. A crash between these commits repeats the record, which the database receipt deduplicates. This is at-least-once delivery with idempotent database effects, not a distributed exactly-once transaction. See [Spring Kafka offset commit modes](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html).

Consumer failures retry without silently skipping a record. Malformed JSON, an unsupported schema, a mismatched key, or conflicting event content blocks progress past that record on its partition until corrected operationally. Other records on that partition can therefore be delayed. This milestone has no dead-letter topic or operator repair/replay workflow; see [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

The projection is eventually consistent: a successful payment response can precede projection creation/update. GET returns structured 404 when no projection exists, including payments whose older operations had no outbox events. The projection is available as a database read even when the current transport is local, but local inbox delivery does not populate it.

The Compose Kafka profile is a single-node KRaft learning setup. Its one broker provides no broker-failure redundancy; `acks=all` does not create extra replicas. It is not a production deployment. Explanation of the choices, failure windows, and alternatives is in [the Milestone 5 learning guide](docs/MILESTONE_5_LEARNING_GUIDE.md).

## Event operations and recovery

Milestone 6 adds a local operator CLI for outbox overview, blocked-event metadata, Kafka lag, consumer failure evidence, and audited DEAD-event requeue. It starts without HTTP, scheduling, Kafka listeners, or automatic topic creation. It uses the application's configured database and runs pending migrations on startup.

```powershell
.\mvnw.cmd verify
java '-Dloader.main=com.fincore.payments.operations.EventOperationsCli' `
    -cp 'target/fincore-payments-0.0.1-SNAPSHOT.jar' `
    org.springframework.boot.loader.launch.PropertiesLauncher overview
```

Milestone 6 commands are `overview`, `events`, `lag`, `failures`, `requeues`, and `requeue`; Milestone 7 adds the consumer recovery commands described below. `help` lists exact options. Results are JSON on stdout, with diagnostics on stderr. Source requeue requires event UUID, expected generation, unique request UUID, operator, and reason. Retrying an identical accepted command returns its saved result. Each new recovery cycle resets its bounded cycle budget while preserving lifetime attempts, envelope, and payment ordering. State change and immutable audit commit together, including database enforcement.

Kafka consumer failures retain original source-position evidence privately; operator lists expose metadata only. Successful records retain compact position/fingerprint markers so a late failure from an old partition owner cannot reopen already successful work. This adds storage per broker record; retention remains unimplemented. `FINCORE_KAFKA_SOURCE_NAME` defaults to `primary` and identifies a logical cluster/topic generation. Rotate it deliberately when changing that source generation; it does not reset event receipts or rebuild a projection.

Lag distinguishes disabled, known, uninitialized, inconsistent, and unavailable states. An unknown total is null, not zero. Read the [operations runbook](docs/EVENT_OPERATIONS_RUNBOOK.md), [Milestone 6 learning guide](docs/MILESTONE_6_LEARNING_GUIDE.md), and [session summary](docs/MILESTONE_6_SUMMARY.md) before source recovery. Source requeue remains separate from consumer quarantine/original replay in Milestone 7; arbitrary consumer correction remains future work.

## Tests and build

```sh
./mvnw test             # Domain, service, and MVC tests; no database required
./mvnw verify           # Above + PostgreSQL Testcontainers integration tests + packaged JAR
./mvnw -DskipTests package
java -jar target/fincore-payments-0.0.1-SNAPSHOT.jar
```

Windows equivalents: `.\mvnw.cmd test` and `.\mvnw.cmd verify`.

Integration tests end in `IT` and run through Maven Failsafe during `verify`. Testcontainers starts isolated PostgreSQL 17.6; it does not use the Compose database or H2. Tests cover lifecycle/optimistic locking, idempotency, ledger constraints and rollback, plus outbox atomicity, local delivery, ordering, retry/dead handling, and concurrent workers.

The Kafka suites also start Apache Kafka 3.9.1 and verify HTTP-to-broker-to-projection delivery, identical redelivery, broker success followed by outbox rollback, and database failure before consumer offset acknowledgement. Concurrent receipt/projection tests run against real PostgreSQL.

When Docker is unavailable, integration tests are **skipped**, not passed. Check `target/failsafe-reports` for skips. Unit/MVC reports are in `target/surefire-reports`. Actual verification results for each milestone are recorded in [docs/SESSION_LOG.md](docs/SESSION_LOG.md), with a [concise Milestone 5 summary](docs/MILESTONE_5_SUMMARY.md).

Optional local PostgreSQL suites run the same idempotency, ledger, outbox, and consumer-projection database assertions against an explicitly supplied PostgreSQL database. **Use a dedicated disposable test database: test cleanup truncates payment, idempotency, ledger, event, receipt, and projection tables.** TRUNCATE is privileged test/administrative cleanup and is not exposed through the application; it bypasses row-level UPDATE/DELETE immutability guards. Without `FINCORE_TEST_DB_URL` the opt-in suites are skipped.

```powershell
$env:FINCORE_TEST_DB_URL = 'jdbc:postgresql://localhost:55432/fincore_milestone5_check'
$env:FINCORE_TEST_DB_USERNAME = 'fincore_check'
$env:FINCORE_TEST_DB_PASSWORD = ''
.\mvnw.cmd verify
```

To include the local broker suite, also set `FINCORE_TEST_KAFKA_BOOTSTRAP` to a dedicated test broker, for example `127.0.0.1:19092`. Both PostgreSQL and Kafka must be disposable: these tests truncate application tables and publish retained records. Without this variable the local broker suite is skipped.

## Controlled consumer recovery

Milestone 7 extends the same local CLI with `gaps`, `quarantines`, `quarantine`, and `replay`. Quarantine requires an existing open permanent failure, its exact fingerprint and explicit acceptance of a projection gap. It permits the listener to acknowledge that position while retaining immutable evidence and a DEGRADED report. It never marks the record processed.

Original replay validates the preserved decoded key/value through the live decoder and atomically commits its receipt, version-guarded projection, success markers and immutable replay audit. A malformed original remains a gap; replacement JSON and forced resolution are not available. Live consumption now commits the receipt/projection and source-position resolution in the same transaction under the shared position lock.

Read the [consumer recovery runbook](docs/CONSUMER_RECOVERY_RUNBOOK.md), [learning guide with alternatives](docs/MILESTONE_7_LEARNING_GUIDE.md), and [Milestone 7 summary](docs/MILESTONE_7_SUMMARY.md). Keep CLI and listener on the updated build. The policy belongs to this read projection; it is not automatically appropriate for financial consumers.

## Projection reconciliation and coverage

Milestone 8 adds `Invoke-EventOps reconcile [--payment-id=UUID | --after-payment-id=UUID] [--limit=50]`. It compares co-located source payments/outbox events with logical consumer receipts/projections and reports versions, missing/conflicting inputs, snapshot drift, retained-history gaps and potential current source replay/rebuild inputs. It includes consumer-only artifacts and both configured-scope and other-scope recovery gaps. No broker connection or repair is performed.

The report uses a read-only REPEATABLE READ transaction, bounded UUID pages and a 30-second transaction timeout. Receipt matching uses event identity and JSONB logical content. `historicalCompleteness` remains NOT_CERTIFIED, and page status applies only to returned rows. Source namespaces do not filter logical consumer receipts/projections. A candidate input is planning metadata; normal deduplicated replay does not rebuild a missing projection under an existing receipt.

Read the [reconciliation runbook](docs/PROJECTION_RECONCILIATION_RUNBOOK.md), [Milestone 8 learning guide](docs/MILESTONE_8_LEARNING_GUIDE.md), and [session summary](docs/MILESTONE_8_SUMMARY.md).

## Isolated shadow projection rebuild

Milestone 9 adds `rebuild-create`, `rebuild-step`, `rebuild-status`, `rebuild-validate` and `rebuild-catch-up` to the local CLI. A run gets a generated `shadow-<run UUID>` logical consumer and frozen source-input manifest. Bounded steps atomically apply shadow receipts/projections and durable item progress. Missing/invalid inputs remain blocked rather than being synthesized.

Catch-up appends a new audited generation with expected-generation/request checks; earlier manifests remain immutable. Validation compares shadow, manifest and current source, including new payments and recovery gaps. Latest-state agreement remains NOT_CERTIFIED for historical coverage. An isolated target reports `REQUIRES_AUTHENTICATED_PROMOTION`; a maintained target reports `ENROLLED_LIVE_FANOUT`; a retired target reports `RETIRED_EVIDENCE_RETAINED`. Rebuilding itself does not switch readers, and no payment, ledger, source-delivery or Kafka-offset operation is replayed.

Read the [shadow rebuild runbook](docs/SHADOW_PROJECTION_REBUILD_RUNBOOK.md), [Milestone 9 learning guide](docs/MILESTONE_9_LEARNING_GUIDE.md), and [session summary](docs/MILESTONE_9_SUMMARY.md). Planning/catch-up scan source data and retain copied evidence; no production-volume or automatic retention guarantee is claimed.

## Authenticated projection promotion and rollback

Milestone 10 adds `GET /api/v1/operations/projection-routing`, `GET .../csrf`, `POST .../promote` and `POST .../rollback`. Set both `FINCORE_OPERATIONS_USERNAME` and `FINCORE_OPERATIONS_PASSWORD_HASH` (a raw BCrypt verifier) to enable the servlet operator boundary. With neither set, this surface denies access; partial/invalid values fail servlet startup. Requests use Basic authentication, the operator role and, for POST, a token/session obtained from `/csrf`. The actor comes from the authenticated principal. Use TLS outside loopback. Milestone 16 separately protects payment endpoints with database-backed merchant credentials and ownership checks.

Promotion rechecks a completed build, current source/outbox/receipts, target agreement and known gaps inside a fenced database transaction. Both baseline and shadow must already be healthy/current/equivalent; this cutover cannot repair a damaged baseline. Immutable decisions, request UUID replay and explicit integral expected route revisions protect retries and stale intent. One SQL statement selects the projection route and row. The consumer maintains baseline and one current shadow together, including duplicate deliveries. Rollback selects the baseline after fresh checks and keeps the same shadow warm for re-promotion.

Source-to-projection updates remain asynchronous, and `LOCAL` inbox delivery does not run the projection consumer. Enrollment freezes the run's rebuild metadata permanently; later live updates can differ from its historical manifest. Read the [routing runbook](docs/PROJECTION_ROUTING_RUNBOOK.md) and the historical [Milestone 10 learning guide](docs/MILESTONE_10_LEARNING_GUIDE.md).

## Controlled projection target lifecycle

Milestone 11 adds authenticated `POST .../replace`, `POST .../retire` and `GET .../targets/{runId}` under the same projection-routing prefix, operator role and CSRF boundary. Replacement requires explicit baseline rollback, the expected previous target/current revision, and a fresh isolated candidate's generation. It rechecks old/new targets, baseline, current source and recovery gaps under the fence, atomically retires the old target and enrolls/selects the new one. Normal fanout remains bounded to baseline plus one shadow.

Retirement requires baseline reads and fresh data/gap agreement, advances the revision and releases shadow maintenance. It retains receipts, projections, manifests and immutable decisions. Retired targets stay frozen and cannot be re-enrolled. A new isolated run can subsequently be promoted without resetting route history. Target inspection distinguishes `ISOLATED`, `MAINTAINED` and `RETIRED`; exact accepted retries return their historical result without changing today's target.

V11 upgrades existing V10 routing history without changing prior IDs, timestamps, revisions or response meaning. It does not purge data or add retention scheduling. See the [Milestone 11 learning guide](docs/MILESTONE_11_LEARNING_GUIDE.md) and [verification summary](docs/MILESTONE_11_SUMMARY.md).

## Retained evidence review and operating limits

Milestone 12 adds authenticated `GET /api/v1/operations/projection-routing/evidence` and `GET .../operating-limits`. Evidence pages use a read-only Repeatable Read snapshot, UUID continuation and a 1–100 row limit. They report lifecycle, the target's retirement decision/time, counts across retained generations/artifacts and logical text-copy bytes. Runs are scoped to the configured source/topic; payloads, merchant references and free-text audit content are not exported.

`FINCORE_PROJECTION_REVIEW_AFTER_DAYS` defaults to 30 (1–3650) and classifies review age after retirement. Every target remains `deletionPermitted=false`. `FINCORE_PROJECTION_MAX_COMPARISON_ROWS` defaults to 100000 (1–1000000); each new routing decision checks source payments, baseline/selected projections and selected current manifests under the fence before larger comparisons. Oversized work returns `409 PROJECTION_COMPARISON_BUDGET_EXCEEDED` without decision writes. Exact accepted replay retains its historical semantics. Invalid settings fail startup.

Operating diagnostics expose four fixed action labels with counts, in-flight work and total/max elapsed nanoseconds through transaction completion. They cover this process's HTTP routing commands, reset on restart and exclude authentication/binding failures, direct service/CLI work and network/serialization time. They are not durable history or a cluster monitoring/export platform. Logical text bytes are not physical/reclaimable disk space, and the row guard is not a general query-cost or latency guarantee.

Read the [evidence runbook](docs/PROJECTION_EVIDENCE_RUNBOOK.md), [Milestone 12 learning guide](docs/MILESTONE_12_LEARNING_GUIDE.md) and [verification summary](docs/MILESTONE_12_SUMMARY.md).

## Audited bounded purge planning

Milestone 13 adds authenticated `POST /api/v1/operations/projection-routing/purge-plans`, `GET .../purge-plans/{requestId}` and `GET .../purge-plans/{requestId}/review`. Creation requires one explicitly named retired target past the configured review window and an exact current route revision. It freezes at most 1,000 combined run/generation/manifest/receipt/projection/routing records as sorted identities and SHA-256 row fingerprints, together with retirement evidence, attributed intent, policy and preservation obligations. Oversized work saves no partial plan. No artifact payload bodies are exported.

Creation uses the existing nonwaiting routing fence in its own Read Committed transaction. The authenticated principal supplies the actor; POST requires CSRF. Exact accepted retries return their original saved plan, even after route changes. V13 adds immutable planning storage and retirement/foreign-key checks without relaxing earlier evidence guards. Fresh review uses a separate read-only Repeatable Read snapshot and reports changed route, policy, retirement evidence or artifact content. Every plan/review has `deletionPermitted=false`; these plan endpoints grant no approval or deletion authority. Milestone 14 below adds preservation and preservation-review approval; physical deletion remains unavailable. Fingerprints are comparison evidence, not signatures or archives; the row cap is not a byte/latency guarantee. Existing four-action routing metrics retain their original scope.

Read the [Milestone 13 learning guide](docs/MILESTONE_13_LEARNING_GUIDE.md), [purge-plan runbook](docs/PROJECTION_PURGE_PLAN_RUNBOOK.md) and [verification summary](docs/MILESTONE_13_SUMMARY.md).

## Verified preservation exports and authenticated review approval

Milestone 14 adds authenticated `POST .../purge-plans/{planId}/preservations` and `/approvals`, metadata GETs under `.../preservations/{requestId}` and `.../approvals/{requestId}`, exact-byte `GET .../preservations/{requestId}/export` and fresh `GET .../approvals/{requestId}/review`, all under `/api/v1/operations/projection-routing`. They reuse the operator role, principal actor and POST CSRF boundary.

Preservation freezes the original plan and every named record body, verifies per-record hashes and the whole-file digest, and stores the exact serialized JSON as immutable TEXT. The combined 1,000-record cap remains; raw bodies and final UTF-8 export each have an 8 MiB limit. Downloads verify the saved copy and return attachment, byte-length, SHA-256 and no-store headers. These files contain full sensitive artifact bodies. The saved copy is in the same database; download permits separate storage but does not prove its durability or restore readiness.

New preservation and approval own fresh fenced Read Committed transactions and refuse stale plans. Approval binds one plan/copy/digest and explicit JSON boolean acknowledgement, with authenticated actor/reason. Exact accepted retries recover historical intent. Approval review owns a read-only Repeatable Read snapshot and reports current plan/copy blockers. V14 adds immutable copy/approval storage and integrity/composite-key checks, preserving V1–V13 guards. All assessments retain `deletionPermitted=false`; this is preservation-review approval, with no deletion executor, automatic retention, external-backup verification or restore API. Existing routing metrics retain their four-action scope.

Read the [Milestone 14 learning guide](docs/MILESTONE_14_LEARNING_GUIDE.md), [preservation runbook](docs/PROJECTION_PRESERVATION_RUNBOOK.md) and [verification summary](docs/MILESTONE_14_SUMMARY.md).

## Approval lifecycle and independent preservation-file verification

Milestone 15 adds fixed validity to new approvals through `FINCORE_PROJECTION_APPROVAL_VALIDITY_MINUTES` (default 60, range 1–1440). Each accepted approval snapshots its duration and expiry. Configuration changes affect new approvals only. V14 approval audit fields remain unchanged; their null validity is reported as `APPROVAL_VALIDITY_UNSPECIFIED` on fresh review. Review also reports expired, uncertain-time or revoked approval alongside existing plan/copy blockers. Exact accepted retries remain historical retrieval after expiry or withdrawal.

Authenticated `POST .../approvals/{approvalId}/revoke` accepts a request UUID and reason; `GET .../approvals/{approvalId}/revocation` retrieves the immutable audit. Withdrawal requires identity/scope, principal actor and POST CSRF, and can succeed for stale, expired or corrupt-copy approvals. A dedicated nonwaiting transaction lock keeps it independent of route freshness and preservation-body verification. It withdraws one approval without erasing its original decision or copy.

The packaged JAR also verifies a downloaded bundle against independently trusted metadata and the original saved plan without starting Spring, PostgreSQL or Kafka:

```powershell
java -jar target/fincore-payments-0.0.1-SNAPSHOT.jar verify-preservation `
  --bundle=projection-preservation.json `
  --metadata=preservation-metadata.json `
  --plan=original-plan.json
```

It checks exact file bytes, envelope, manifest and every body hash using strict UTF-8/JSON parsing and bounded reads. Exit 0 reports agreement with the supplied trusted references; exit 2 reports a sanitized refusal. This does not prove reference authenticity, durable external storage, restore readiness or current approval validity. Every result retains `deletionPermitted=false`. No server verification receipt or restore/deletion executor is added.

Read the [Milestone 15 learning guide](docs/MILESTONE_15_LEARNING_GUIDE.md), [approval lifecycle and offline-verification runbook](docs/PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md) and [verification summary](docs/MILESTONE_15_SUMMARY.md).

## Merchant authentication and tenant isolation

Milestone 16 protects payment, ledger, event and projection HTTP endpoints with database-backed merchant Basic authentication and BCrypt. `GET /api/v1/merchant/me` returns safe identity; `GET .../csrf` establishes the token/session required for POST. Credentials are checked on each request; the CSRF cookie alone does not authenticate. A local merchant authentication manager prevents fallback to operator credentials, and unmatched routes are denied.

`MerchantPaymentService` derives the merchant UUID from its authenticated principal and verifies immutable payment ownership before reads/transitions and subordinate views. Body/query/header merchant IDs do not select scope. Foreign and missing UUIDs share 404. Creation atomically stores the owned payment, outbox event and original response under `(merchant_id,idempotency_key)`; a deferred composite FK rejects cross-merchant claims. Ledger balances aggregate only owned payment journals and return no accounts when the merchant has no postings.

V16 preserves old payment/claim fields and assigns their unknown ownership to a disabled legacy identity with no login. Provisioning does not adopt those records. Existing account definitions and retained event envelopes remain unchanged; a source-less consumer projection cannot grant merchant HTTP access. Trusted operations/database access remains cross-merchant; this is application isolation over one shared database, not RLS or a wallet/account hierarchy.

Read the [Milestone 16 teaching guide](docs/MILESTONE_16_LEARNING_GUIDE.md), [merchant setup and access runbook](docs/MERCHANT_ACCESS_RUNBOOK.md), [verification summary](docs/MILESTONE_16_SUMMARY.md) and [completion roadmap](docs/ROADMAP.md).

## Sandbox payment-provider integration

Milestone 17 adds `POST /api/v1/payments/{paymentId}/provider-operations` with a required command body:

```json
{"requestId":"11111111-1111-4111-8111-111111111111","action":"AUTHORIZE","sandboxScenario":"APPROVE"}
```

Use a fresh merchant-scoped request UUID for a new logical AUTHORIZE, CAPTURE or REFUND, and preserve the exact body for retries. `APPROVE`, `DECLINE` and `TIMEOUT_AFTER_ACCEPT` are local sandbox scenarios. HTTP 200 returns progress; UNKNOWN is uncertainty, not a provider decline or approval. Retry the original command to recover its saved provider decision. A new key is refused while an unfinished command exists.

`ProviderOperationService` commits intent and a leased attempt before calling `HttpSandboxPaymentProvider` outside database transactions. A confirmed, identity-matched response commits operation progress, payment, ledger and outbox together. Lease tokens fence old workers; the unchanged operation UUID is the provider idempotency key. First authorization binds a CREATED payment to the sandbox instance; manual authorize/capture/fail/refund then return 409. Existing unbound manual flows remain available.

Integration defaults to `FINCORE_PROVIDER_ENABLED=false`. Enabling it requires `FINCORE_PROVIDER_BASE_URL` (loopback), `FINCORE_PROVIDER_API_KEY` and nonzero `FINCORE_PROVIDER_INSTANCE_ID`; timeout defaults to 2,000 ms and lease to 30 seconds. The separate executable-JAR `provider-sandbox` mode uses `FINCORE_SANDBOX_API_KEY`, `--port`, `--data-dir` and `--instance-id`; it starts before Spring without PostgreSQL/Kafka. Retain its original instance and journal for restart-safe exact retries. Local file persistence is tested across process restart, not certified for power loss or independent backup/restore.

Read the [Milestone 17 learning guide](docs/MILESTONE_17_LEARNING_GUIDE.md), [provider setup/recovery runbook](docs/PROVIDER_INTEGRATION_RUNBOOK.md) and [test summary](docs/MILESTONE_17_SUMMARY.md). No live vendor, real funds or automatic provider retry worker is included. Optional incoming callbacks are added in Milestone 18 below.

## Verified incoming provider webhooks

Milestone 18 verifies HMAC-SHA256 over the exact bounded body, key ID and fresh delivery timestamp before parsing or database work. The receiver freezes one receipt per provider-instance/event identity and commits its disposition together with any command/payment/ledger/outbox completion. Exact duplicates return the original receipt; changed bytes under the same event identity conflict. Matching old results are recorded without rewinding later payment state or duplicating journals. Unknown/foreign/unstarted commands and contradictory results cannot force financial changes.

Callbacks can confirm existing UNKNOWN or IN_FLIGHT commands, even when outbound execution is disabled. Merchant-owned receipt metadata is available under each provider operation. Raw payloads and credentials are omitted. The receiver defaults off and requires `FINCORE_PROVIDER_WEBHOOK_ENABLED`, `FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY`, `FINCORE_PROVIDER_WEBHOOK_KEY_ID` and `FINCORE_PROVIDER_WEBHOOK_INSTANCE_ID`; timestamp tolerance defaults to ±300 seconds.

The standalone sandbox can optionally queue notifications using `FINCORE_SANDBOX_WEBHOOK_URL`, `FINCORE_SANDBOX_WEBHOOK_KEY` and key ID. Its authenticated `POST /v1/webhooks/{operationId}/deliver` resends a persisted decision after failure/restart using a stable event/body and fresh signature. Automatic sending is bounded best effort, with no durable retry scheduler; it is a local test aid, not merchant notification delivery.

Read the [Milestone 18 learning guide](docs/MILESTONE_18_LEARNING_GUIDE.md), [callback runbook](docs/PROVIDER_WEBHOOK_RUNBOOK.md) and [test summary](docs/MILESTONE_18_SUMMARY.md).

## Current limitations and next milestones

This service records payment state and ledger obligations and can call its separate local HTTP sandbox provider. Merchant HTTP operations are authenticated and scoped, while operator/local tools remain privileged and the separate observer has read-only telemetry access. Account definitions are pooled per currency; posted totals are merchant-filtered. Local amount mode supports partial captures, capture-specific refunds and explicit retained fees; provider commands/callbacks remain full amount. Reliable merchant notifications, independent sandbox settlement comparison, opt-in Redis admission rules and private observability are implemented through Milestone 23.

**Not implemented:** live vendor adapters/funds, automatic provider/receipt retry or review resolution, receiver key-ring rotation, void/cancel, provider partial commands, fee tariffs/refunds, bank payouts, off-site/physical/PITR and cross-system recovery. Local logical reconstruction and an explicit retain-data decision are supplied in M24. Full identity lifecycle, OIDC/MFA, general API rate limiting, multi-instance session handling, production Kafka security/availability and deployment/load/security hardening remain unfinished. Monitoring lacks durable asynchronous event trace propagation, alert notification routing and production trace retention. Real Redis and Docker/Jaeger UI validation remain outstanding in this environment. No RLS/database-per-tenant boundary, public merchant signup, password-reset or ownership reassignment API is supplied. Idempotency keys have no expiry policy, with immutable response snapshots tied to v1. Physical purge, automatic retention, arbitrary consumer correction and Kubernetes are not implicitly required/completed. `NO_KNOWN_GAPS` remains an observation rather than a historical-completeness certificate.

**Next logical milestone:** 25 — reproducible deployment and final sandbox/failure/load/security validation. The [roadmap](docs/ROADMAP.md) records one remaining planned block. See the [M24 summary](docs/MILESTONE_24_SUMMARY.md) for verified reconstruction and remaining environment/proof limits.
