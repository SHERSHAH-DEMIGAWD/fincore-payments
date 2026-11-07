# Fincore Payments Milestones 21 to 25

**Implementation report and learning guide**

**Evidence through 28 October 2026**

Milestones 21–25 complete the agreed functionality roadmap for the independent Java project `fincore-payments`. They add independent settlement comparison, admission controls, operational visibility, supported database recovery, and a reproducible local release with packaged acceptance. All 25 planned functionality blocks are implemented. Actual Docker execution and real Redis combined validation remain outstanding on this host; this is a completed learning implementation with explicit validation limits, not a live-payment launch.

This report continues the teaching approach of the [Milestones 1–10 guide](COMPLETE_LEARNING_GUIDE.md) and [Milestones 11–20 guide](COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.md). It explains what these final five milestones do, why their concepts matter, how the code uses them, what alternatives were considered, and what the available evidence establishes. The two earlier Word volumes remain historical snapshots.

## 1 Project purpose and the starting point

We are building a distributed payment learning backend to understand what happens when requests repeat, two writers race, an external provider commits before a connection fails, or a recovered database must explain old financial decisions. The goal is to connect HTTP behavior to durable evidence and balanced accounting. It is separate from the unrelated `ledger-mvp` project.

Before Milestone 21, the system already had merchant ownership, payment lifecycle rules, exact creation retries, immutable double-entry journals, a transactional outbox, Kafka delivery and derived projections. It also had controlled projection maintenance, a local HTTP provider, signed incoming callbacks, reliable merchant notifications, and separate local partial capture/refund accounting with retained fees.

Those capabilities answer how a local command commits and how related distributed work retries. The final five blocks answer further operational questions: does external evidence agree, which new requests should enter, what is stalled, can database evidence be reconstructed, and can the assembled executable demonstrate those behaviors repeatably?

| Milestone | Functionality added | Main question |
| --- | --- | --- |
| 21 | Signed independent settlement statements and reconciliation | Does the provider journal agree with confirmed local operations? |
| 22 | Redis admission and durable deterministic risk evidence | Should this new creation intent enter now? |
| 23 | Private metrics, traces, backlog sampling and alert rules | Which work is failing or falling behind? |
| 24 | Consistent logical backup and isolated restore verification | Can a supported database image be reconstructed and checked? |
| 25 | Verified release, isolated deployment recipe and packaged acceptance | Do the assembled artifact and supported flows work together? |

Read chapters 2–8 for the concepts and worked flows, chapters 9–12 for tradeoffs and failure handling, and chapters 13–16 for evidence, source navigation and the learning handoff. Runbooks contain executable operating instructions; this report explains their purpose.

## 2 Authority evidence and system boundaries

Several systems hold different kinds of state. Treating every successful response or diagnostic counter as equivalent evidence would defeat the purpose of the design.

| Component | Responsibility | Important boundary |
| --- | --- | --- |
| PostgreSQL | Payment intent/state, journals, exact retries, commands and audit evidence | Financial authority for the local application |
| Provider journal | Independent sandbox decisions | A separate simulated system; no live bank |
| Kafka and projections | Event transport and derived reads | Rebuildable views do not replace journals |
| Redis | Short-lived shared admission capacity | Loss can replenish a soft quota |
| Metrics and traces | Diagnosis and correlation | Sampled/resettable observations do not prove money movement |
| Backup manifest | Reference fingerprints and archive identity | Requires trusted custody and does not contain external keys |
| Release manifest | Tested JAR identity and recorded suite results | Skips remain skips; no production certification |

The core local transaction remains payment changes plus related journals, outbox events and enrolled notification intent. Provider decisions, Kafka processing and merchant receipt delivery have separate commits. Stable identities and repeatable completion bridge these boundaries; there is no single transaction covering all systems.

```text
Merchant request and CSRF session
→ optional admission assessment and durable decision
→ payment transaction in PostgreSQL
  → outbox → Kafka → receipt and projection
  → merchant delivery → signed receiver receipt

Provider command → independent provider journal
→ confirmed reply or verified callback → local financial application
→ independent signed statement → comparison report

Private observation and database recovery inspect these boundaries
Verified packaging makes their assembled demonstration repeatable
```

This is why the final milestones matter together. Admission controls reduce new work; they do not repair accepted work. Telemetry points to a problem; it does not authorize a financial correction. Reconciliation compares evidence; it does not invent a capture. Restore verification compares a reconstructed database; it does not recover a provider's separate history.

## 3 Milestone 21 Independent settlement reconciliation

### The problem and the implemented behavior

Suppose the provider approves a capture and persists its decision, but its reply disappears. The application may correctly retain UNKNOWN while the provider journal already records acceptance. Comparing a local projection with the local payment can miss this discrepancy because both derive from the same incomplete local history.

M21 adds a signed cumulative statement generated from the sandbox provider's own durable journal. It lists approved full-amount capture/refund decisions for one merchant/currency; authorization and declined decisions do not become statement financial lines. The merchant imports the original authenticated bytes, requests an immutable comparison report, inspects discrepancies, and may record attributed resolution after fresh matching evidence.

The term settlement here names an evidence/comparison capability. No bank transfer, merchant payout or live settlement execution is implemented. The contract is full provider principal with zero expected fees. M20's local partial accounting and retained fees remain a separate supported mode.

### Concepts used in the code

**Independent reconciliation** compares operation identities and supporting evidence from two committing systems. Equal net totals alone could hide one missing capture offset by one missing refund, so comparison is operation-level and also checks confirmed LIVE journals.

**HMAC and exact bytes** authenticate the provider document with a shared key. The receiver verifies the original UTF-8 body, rather than parsing and pretty-printing JSON before checking it. Formatting changes alter the authenticated message. Strict JSON validation, canonical UUIDs and exact decimals reduce ambiguous representations. HMAC authenticates a key holder; it is not public verification or nonrepudiation.

**Immutable observations and Repeatable Read** preserve what a report saw. A report request UUID replays its historical accepted result. A new report gets a new observation. Resolution requires a matching report for the same statement and another fresh comparison with the expected source fingerprint. This prevents an old favorable report from authorizing closure after relevant local state changes.

### Actual request flow

```text
GET provider /v1/settlements/{merchantId}/{currency}
→ SandboxProviderServer.settlement
→ SandboxProviderStore.settlement
→ SettlementWire.encode and signature

POST /api/v1/merchant/settlement-statements
→ SettlementController.importStatement
→ SettlementImportVerifier.verify
→ SettlementService.importStatement → SettlementRepository

POST /{statementId}/reports
→ SettlementController.reconcile → SettlementService.reconcile
→ comparison of stored statement and local evidence
→ SettlementRepository.insertReport

POST /{statementId}/reports/{reportId}/resolve
→ SettlementController.resolve → SettlementService.resolve
→ fresh matching evidence → insertResolution
```

### Worked recovery example

For a simulated INR 100 capture, the provider statement can show acceptance while the local command remains UNKNOWN and the local capture journal is absent. The report records that disagreement. Recovery uses the existing provider command identity or a verified callback; reconciliation itself never calls the provider application path or posts journals. After legitimate confirmation, a new report for the same statement can match. Resolution still checks fresh evidence before recording closure.

A later refund or a newer local command can make an older cumulative statement unsuitable for closure. Its horizon is a provider journal sequence, not proof of the latest wall-clock state. Another statement cannot silently close the old discrepancy. There is no force-close or cross-horizon correction feature.

### Design choices and limits

Signed statement bytes were chosen over an unsigned local export because the source and message identity must be attributable. Operation-level comparison was chosen over net balance comparison because the explanation of a discrepancy matters. Immutable reports were chosen over a mutable reconciled flag because historical observations must remain inspectable. These choices add retained rows, strict contracts and key-custody obligations.

Statements are capped at 1 MiB and 1,000 external lines; local command comparison refuses above 1,000, and reports are capped at 2 MiB/2,000 union items. Excess fails rather than producing partial matching evidence. These caps do not promise a total request deadline or production scan capacity. Merchant authentication, POST CSRF and ownership checks still apply.

Primary code: `SettlementController`, `SettlementImportVerifier`, `SettlementWire`, `SettlementService`, `SettlementRepository`, `SandboxProviderStore`, and migration `V21__add_sandbox_settlement_reconciliation.sql`. Read the [M21 guide](MILESTONE_21_LEARNING_GUIDE.md) and [settlement runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md).

## 4 Milestone 22 Admission controls and risk evidence

### The problem and the implemented behavior

A correct payment transaction can still be overloaded by too many new intents or receive unsupported original amounts/currencies. M22 adds opt-in deterministic screening to new authenticated HTTP payment creation. When enabled, its defaults are INR, maximum original principal INR 100,000.00, and 20 newly admitted intents per merchant/policy window of 60 seconds. It defaults off.

This is rule-based admission, not learned fraud detection. Authorize, capture, refund, callbacks, settlement, recovery and reads do not consume this quota. Existing accepted financial creation retries are checked first and bypass a new Redis decision. Recovering old work should not depend on acquiring a new admission slot.

### Why Redis and PostgreSQL both appear

Redis coordinates temporary shared capacity across instances. One Lua operation checks a token, counts admission and establishes expiry atomically. Separate GET/INCR/EXPIRE commands could interleave; a per-JVM map would split capacity by process. A hash holds `used` plus admitted-intent markers, bounded by the configured allowance through this path. The window begins with the first admission and later traffic does not extend its TTL.

PostgreSQL binds the creation intent and retains immutable policy decisions. A hashed token includes both the creation key and canonical request fingerprint, so an unchanged retry can reuse a surviving reservation. Raw request keys and business payloads are not stored as Redis markers. Redis remains temporary coordination state; PostgreSQL remains financial authority.

### Transaction and request flow

```text
PaymentController.createPayment
→ MerchantPaymentService.createPayment
→ PaymentRiskService.requireAdmission
→ PaymentRiskTransactions.assess
  → PaymentRiskRepository fence and intent checks
  → static currency and original amount checks
  → RedisRiskVelocity.reserve → payment-admission.lua
  → immutable decision → evidence transaction commits
→ PaymentService.createPayment
→ ordinary payment retry and financial transaction
```

The orchestration uses `NOT_SUPPORTED`; assessment uses its own `REQUIRES_NEW` Read Committed transaction. Refusal is thrown after the decision transaction commits. Otherwise an HTTP refusal could roll back the very evidence needed to explain it. Financial creation then commits separately. This deliberately avoids a distributed transaction between Redis and PostgreSQL.

### Failure behavior and its consequences

| Observation | HTTP behavior | Retry meaning |
| --- | --- | --- |
| Static DENY | 422 | Retained terminal decision while risk remains enabled |
| THROTTLED | 429 with Retry-After | Unchanged intent can be reassessed later |
| Redis UNAVAILABLE or uncertain reply | 503 with Retry-After | Refuse new admission; retain diagnostic observation |
| Existing ALLOW | Continue to financial creation | Saved grant is reusable |
| Existing committed payment retry | Historical accepted response | No new policy or Redis admission |

Refusals expose `Fincore-Risk-Decision-Id`; owned history is available under `/api/v1/merchant/risk-decisions`. Corrupt Redis state is refused, not erased to make the request pass.

If a Redis reply is lost, the server may have consumed a slot. A stable token can recover that reservation if it survives. If financial creation rolls back after ALLOW commits, the saved ALLOW can be reused. The implementation does not issue a speculative compensating decrement: that could release someone else's valid capacity after an ambiguous outcome.

Redis expiry or data loss can replenish the quota. Saved ALLOW has no expiry/revocation, and inconsistent enabled/disabled or policy settings across instances weaken admission control. Therefore this is a soft workload control, not a hard durable financial exposure limit. A durable accounting limit would need a different invariant and lifecycle.

Primary code: `PaymentRiskService`, `PaymentRiskTransactions`, `PaymentRiskRepository`, `RedisRiskVelocity`, `RiskProperties`, `PaymentRiskController`, `redis/payment-admission.lua`, and migration `V22__add_payment_risk_evidence.sql`. Read the [M22 guide](MILESTONE_22_LEARNING_GUIDE.md) and [risk runbook](PAYMENT_RISK_RUNBOOK.md).

## 5 Milestone 23 Private observability

### What operators can now see

M23 adds opt-in private Prometheus metrics and health endpoints, bounded flow counters/timers/histograms, sampled traces with optional OTLP HTTP export, trace-ID correlation, cached operational backlog gauges and eleven alert rules. It introduces no schema migration; V22 remains current.

The observer is a separate read-only identity with an isolated authentication manager and BCrypt verifier. Four GET routes are permitted under `/internal/observability`: Prometheus, aggregate health, readiness and liveness. Merchant/operator credentials do not gain observer access, and the observer gains no merchant or recovery authority.

### Metrics traces and transaction completion

A counter records a number of observed calls or outcomes. A timer records observed durations; histogram buckets support approximate percentiles. A trace connects sampled spans from one request. They answer diagnostic questions at different levels. Retries may produce multiple calls, counters reset, and traces may be sampled or dropped. None is an authoritative count of settled money.

`FlowObservationAspect.observe` surrounds explicit service boundaries through `FlowTelemetry.observe`. Advice surrounds ordinary transaction completion, allowing deferred commit failures to be observed. A caller-owned outer transaction can still commit later. Nested durations overlap and must not be added as independent elapsed time.

```text
PaymentController.createPayment
→ FlowObservationAspect.observe → FlowTelemetry.observe
→ MerchantPaymentService.createPayment
→ optional risk assessment → PaymentService.createPayment
→ PostgreSQL commit → PaymentResponse
→ fixed-category metrics and optional sampled spans
```

Fixed labels, route templates, sanitized errors and restricted propagation reduce copied private data and high cardinality. A payment UUID as a metric label would create unbounded series and expose business identifiers. A trace ID is a diagnostic search key, not authorization or financial evidence.

### Cached backlogs and freshness

`OperationalSnapshotCollector` samples global source facts through `OperationalSnapshotRepository.snapshot` in an independent read-only Repeatable Read transaction. It then publishes cached values through `OperationalSnapshot`. Scrapes read the cache rather than running expensive SQL on every request.

The sample covers pending/dead deliveries, uncertain provider work, consumer failures and unresolved discrepant settlement reports. On collection failure, old values remain but availability/freshness signals change. Initial zeros with unavailable status mean unknown, not no backlog. Oldest ages freeze until another successful sample. Multiple replicas repeat global database facts; summing them would double-count.

Readiness checks PostgreSQL. Aggregate health can fail because enabled Redis is unavailable even while readiness is healthy. These endpoint meanings are deliberate and must be read with their configuration. Alert rules use freshness and traffic gates to distinguish absent evidence from a genuine zero.

### Why this design was selected

Explicit AOP boundaries provide interpretable flow categories; instrumenting every repository would add noise and sensitive dimensions. Cached sampling controls scrape cost but introduces stale data. Bounded asynchronous trace export contains ordinary telemetry failure but permits loss. A separate observer credential narrows authority but requires its own custody. Alerts guide authorized human triage rather than mutating financial records automatically.

Primary code: `ObservabilityConfiguration`, `ObservabilitySecurityConfiguration`, `FlowObservationAspect`, `FlowTelemetry`, `OperationalSnapshotCollector`, `OperationalSnapshotRepository`, and the `observability/` rules/configuration. Read the [M23 guide](MILESTONE_23_LEARNING_GUIDE.md) and [observability runbook](OBSERVABILITY_RUNBOOK.md).

## 6 Milestone 24 Logical backup and restore verification

### What a successful backup must establish

A copied archive is useful only if its evidence describes the same database image and an isolated reconstruction preserves the expected relationships. M24 adds headless logical capture and read-only restored-target verification, plus a retention decision that keeps authoritative records. It does not add deletion, automatic cutover or financial schema changes.

`FincorePaymentsApplication.main` dispatches the two commands before Spring starts. This avoids API workers, Redis checks and exporters during recovery tooling. Capture creates a custom-format PostgreSQL dump, manifest, supported schema/row fingerprints and archive identity. Verification requires a separately trusted manifest hash and compares a separately restored database.

### The exported snapshot concept

MVCC lets a transaction observe a coherent database image while supported writers continue. A read-only Repeatable Read source transaction inventories evidence and exports its snapshot. `pg_dump` imports that same snapshot while the source transaction stays open. Two unrelated scans could disagree merely because a legitimate commit happened between them.

```text
create-recovery-backup
→ FincorePaymentsApplication.main → DatabaseRecoveryCli.run
→ DatabaseRecovery.inventory → pg_export_snapshot
→ DatabaseRecovery.dump → pg_dump --snapshot
→ archive and completed manifest

Operator pg_restore into a NEW fincore_restore_* database
→ verify-recovery-restore → DatabaseRecoveryCli.readManifest
→ read-only DatabaseRecovery.inventory → comparison report
```

The archive is checked by length and SHA-256. Inventory covers all supported public tables, canonical row values, supported structural definitions and balanced journal shape. Counts alone cannot detect a changed amount; equal row counts are insufficient. Domain and byte-length prefixes distinguish digest boundaries, and stable ordering removes physical row-order differences.

### Actual reconstruction and an important compatibility lesson

The native drill uses real PostgreSQL dump/restore tools, then starts the recovered API. It verifies that an original CREATED retry response remains historical while current GET can be REFUNDED, foreign ownership still returns 404, partial principal/fee values remain exact, and journal mutation guards still refuse changes. Four final M25 verification reports cover 39-table restored inventories across the retained drill cases.

A real restore exposed equivalent SQL expressions deparsed differently after dump/reparse. `RecoverySchema` normalizes only the observed unbounded-varchar literal-array conversion to text. It preserves values and meaningful definitions and does not normalize arbitrary function bodies. Broadly stripping casts or whitespace could hide a real schema change; refusing every textual difference would reject a valid reconstruction.

The operator restores explicitly with transactional/error-stop flags into a new database. Java commands perform no restore, DROP or source overwrite. The verifier refuses source-as-target and writes a new report. Negative tests cover missing/changed rows, wrong reference, damaged archive, missing index, disabled guards, partial backup failure and overwrite refusal.

### What remains outside this proof

Logical reconstruction on the same host is narrower than a full distributed recovery. External encryption/signing keys, the provider journal/identity, Kafka history, receiver receipts and access grants need separate custody and recovery. Redis loss has its own quota consequences. No off-site/second-host drill, WAL/PITR facility, physical backup, automatic cutover, signed attestation or hard RPO/RTO guarantee is implemented.

SHA-256 establishes agreement with a trusted reference; replacing both a bundle and its only reference can still produce matching hashes. Read-only capture also has operating costs: its snapshot retains old row versions and Access Share locks can delay DDL. Retention is an explicit keep-data decision, not a cleanup executor.

Primary code: `DatabaseRecoveryCli`, `DatabaseRecovery`, `RecoverySchema`, native/packaged recovery tests, and the retention policy. Read the [M24 guide](MILESTONE_24_LEARNING_GUIDE.md), [database runbook](DATABASE_RECOVERY_RUNBOOK.md) and [retention policy](RETENTION_POLICY.md).

## 7 Milestone 25 Reproducible release and packaged acceptance

### Why packaging is part of correctness

Individually tested services can still fail when launched together with real credentials, cookies, network destinations and workers. M25 creates a verified executable release and a bounded end-to-end exercise. It joins the existing financial paths without adding a new money operation or migration.

`Build-LocalRelease.ps1` requires Java 21, runs clean verification, records fresh JUnit results, and creates an allowlisted secret-free ZIP with the exact tested JAR, manifest and checksums. `Verify-LocalRelease.ps1` rejects altered files, missing/duplicate entries, unexpected paths and a manifest-to-JAR mismatch. The Dockerfile verifies the required JAR digest again.

Fixed Maven output timestamps support identical JAR bytes for the same toolchain/source/dependencies. Runtime images are pinned by digest to avoid mutable-tag drift. Two clean same-toolchain builds produced identical JARs in final verification. Generated manifests, random credentials, runtime timings and the ZIP itself are not promised identical across runs. Hashes still require trusted custody; the release is not a signed production attestation.

### Bootstrap and local topology

`LocalSandboxCli` supplies explicit initialization, atomic two-merchant bootstrap and observer-only readiness. SecureRandom creates private inputs; BCrypt verifies browser/observer/operator passwords; a separate master key protects retained subscription secrets. A PostgreSQL advisory transaction lock coordinates bootstrap attempts. Existing accounts must match original credentials; reruns do not rotate or adopt accounts.

The standalone Compose recipe uses non-root Java processes, read-only roots, bounded temporary resources and retained data/evidence volumes. PostgreSQL/Kafka use the private network. Provider, Redis and acceptance share the API network namespace, preserving existing literal-loopback adapter restrictions. Only the API host loopback port is published. This reduces required destination exceptions but couples service lifecycle and is not an HA topology.

### The acceptance flow and failure cases

```text
sandbox-acceptance
→ SandboxAcceptanceCli.run → Exercise.execute
→ readiness and real identity/CSRF/ownership checks
→ subscription and signed MerchantWebhookSink
→ create plus exact/conflicting retries
→ provider authorize and timeout-after-accept capture
→ UNKNOWN → explicit signed callback twice → APPLIED
→ full refund and signed settlement match
→ separate partial capture/refund and retained fees
→ receiver outage → DEAD → restart and authorized retry
→ same-intent race plus distinct concurrent creations
→ outbox delivery and real Kafka projection checks
→ pause subscription and save new report
```

The sandbox provider can disable automatic callbacks for this deterministic drill; ordinary default behavior stays enabled. The test resolves UNKNOWN through the genuine signed endpoint rather than bypassing confirmation. Repeated callback delivery must not duplicate financial effects.

Early acceptance failures exposed shared browser-cookie/CSRF state. The final client uses separate identities and one browser session per worker, obtaining a fresh masked token before writes. Machine traffic has no browser cookie jar. Disabling CSRF would have hidden a client defect rather than fixing it.

`SandboxHttp` limits complete response bytes and elapsed time, including a body that stalls after headers. This is stronger than assuming headers mean the entire response will finish. Acceptance bounds request count/concurrency, reserves a new report before HTTP writes, and refuses overwrite. Failed runs retain evidence; cleanup may require inspection of an active subscription.

### Observed final smoke evidence

The final native run used 24 same-intent requests, 24 distinct creations and 4 workers. It recorded 36 verified notification receipts and checked 28 payment rows, 3 provider operations, 8 journal headers/16 entries, 1 statement report and 1 manual notification retry. Distinct-creation workflow p50 was 281.285 ms, p95 was 320.084 ms and maximum was 367.412 ms; the whole load stage including both phases and delivery waits took 10.333 s.

These are one local smoke run's observations. Token retrieval is included. They are not a sustained throughput result, saturation benchmark or production SLO. The report explicitly sets `productionCapacityValidated=false`. Native acceptance used real Kafka and explicitly set `riskValidated=false` because real Redis was unavailable. The supplied Docker acceptance requires both risk and Kafka checks, but that combined job has not run here.

Primary code/artifacts: `LocalSandboxCli`, `SandboxConfiguration`, `SandboxAcceptanceCli`, `SandboxHttp`, `Dockerfile`, `deployment/compose.yml`, `deployment/entrypoint.sh`, release scripts and the repository-root CI workflow. Read the [M25 guide](MILESTONE_25_LEARNING_GUIDE.md), [deployment runbook](DEPLOYMENT_RUNBOOK.md) and [final validation report](FINAL_VALIDATION_REPORT.md).

## 8 One connected payment story

This is a teaching scenario for the enabled architecture, not a claim that the native M25 run exercised real Redis.

1. Merchant A submits a new INR 100 payment with its creation key and genuine CSRF session. When risk is enabled, static policy and one Redis reservation are assessed and the decision is retained.
2. Payment creation atomically retains the payment, exact retry response and outbox event. A retry recovers that accepted response; it does not create another payment.
3. A full provider capture commits remotely but loses its reply. The local command remains UNKNOWN until a matching reply or verified callback confirms application. Local journals are not invented from uncertainty.
4. An independent signed statement can reveal the missing local confirmation. Reconciliation records the discrepancy; recovery completes the original provider intent through the existing mechanism.
5. Fresh matching evidence permits attributed closure for the same statement. A historical report remains historical after later changes.
6. Notification and Kafka paths can redeliver independently. Their stable IDs protect receiver/projection application; they do not repeat the provider capture.
7. Private telemetry reveals latency, dead work or stale sampling. It guides inspection of durable commands, journals and reports rather than replacing them.
8. A consistent logical backup can be restored into a separate supported database and checked. External keys and journals still require their own recovery plan.
9. The verified release packages the exact tested JAR and supplies repeatable local acceptance for these supported boundaries.

The separate partial-accounting example captures 40 with fee 2 and 60 with fee 3, then refunds each principal against its capture. Retained fees remain 5 and merchant payable becomes -5 under the existing obligation model. This example does not extend the provider's full-amount/zero-fee protocol.

## 9 Major choices alternatives and costs

| Chosen approach | Why it fits here | Alternative and its cost |
| --- | --- | --- |
| Independent operation-level statements | Exposes missing or mismatched external facts | Local-only/net-total comparison can hide offsetting omissions |
| Exact-byte HMAC | Simple explicit signed-message contract | Canonical JSON/public-key signatures need another protocol and key model |
| Immutable report plus fresh closure | Preserves history while checking current evidence | A mutable matched flag loses observation history |
| Redis Lua with stable intent markers | Atomic shared short-lived admission | JVM counters split by process; separate Redis commands race |
| Separate evidence and financial commits | Retains refusal/grant explanation without XA | One distributed transaction adds coordination; conservative slots may remain |
| Private observer and fixed labels | Narrows diagnostic access and series growth | Privileged/public metrics and per-payment labels expand exposure/cardinality |
| Cached backlog sampling | Makes scrape work predictable | SQL per scrape adds load; caching requires freshness handling |
| Logical snapshot plus full fingerprints | Checks exact supported reconstruction | Counts/samples miss changes; physical/WAL recovery solves additional goals |
| Fresh restore target | Preserves source and failed evidence | In-place restore risks operational overwrite |
| Verified prebuilt JAR and image digests | Binds deployment to tested bytes and fixed inputs | A later in-image rebuild or mutable tag may drift |
| Independent browser sessions | Preserves real authentication/CSRF semantics | Disabling CSRF hides client bugs; sessions add client state |

Alternatives are not universally worse. A production system might need public-key verification, durable exposure accounting, a managed secret service, TLS, HA, physical backups or continuous WAL archiving. This project has not implemented or measured those operating contracts. The selected approaches keep the learning scope inspectable while making their costs visible.

## 10 Failure handling across the five milestones

| Symptom | Inspect first | Supported next action |
| --- | --- | --- |
| Capture UNKNOWN | Original command and provider receipt/journal | Retry the same intent or process verified confirmation |
| Statement discrepancy | Exact statement, report items and local confirmed journals | Recover legitimate source work, then request fresh comparison |
| 429 or 503 on new creation | Owned risk decision and configured quota/Redis state | Respect retry guidance; reassess unchanged retryable intent |
| Old or missing gauges | Sample availability/age and collector health | Restore visibility before interpreting cached zeros |
| Merchant notification DEAD | Delivery attempt and receiver signature/journal | Restore receiver, then use authorized retry generation |
| Restore mismatch | Trusted hash, archive and target inventory | Preserve failed target; investigate rather than overwrite source |
| Acceptance 403 during writes | Correct identity, session cookie and current CSRF token | Fix the client session; preserve API security checks |

Do not update a ledger row merely to make a comparison match. Do not use a metric, old report or matching file hash as deletion authority. Do not replace provider identity/keys while retaining incompatible journals. Operational observations support a decision; the supported command's current guards still determine whether it can commit.

## 11 Security consistency and retained evidence

Merchant, observer and operator identities have different authority. Merchant ownership scopes payment, statement, risk and notification evidence. Browser POSTs retain CSRF protection. Machine provider callbacks use their own authenticated protocol. A deployment network namespace or trace ID does not substitute for any of these identity checks.

Different cryptographic tools serve different purposes: BCrypt verifies an original password; HMAC authenticates a shared-key message; AES-GCM protects stored subscription key material; SHA-256 compares bytes with a reference. Keeping all keys inside a database backup would not provide independent custody. Generating new keys on every restart would break retained subscriptions/provider identity.

V21 adds settlement reconciliation evidence; V22 adds payment risk evidence. M23–25 introduce no financial schema migration. Applied earlier Flyway checksums remain unchanged. Hibernate validates the schema rather than silently rewriting it.

The retention decision keeps authoritative records, reports and relevant external resources. Docker log rotation does not purge business evidence. `docker compose down` retains named volumes; no `-v` cleanup is part of the operating recipe. There is no physical deletion executor or automatic archival expiry.

## 12 Holistic impact on the project

M21 adds an independent explanation of disagreement across a remote commit boundary. M22 adds a controlled entry point for new workload and a durable reason for refusal. M23 makes stalls and outcomes visible without granting payment authority. M24 demonstrates supported logical reconstruction and preserved behavior. M25 joins these paths in a tested executable and records exactly which environments were exercised.

These developments make the earlier foundations usable as an assembled learning system. Exact retries, immutable journals and outbox records now participate in comparison, diagnosis, reconstruction and a packaged demo. The project can explain both an accepted outcome and a refusal, distinguish uncertain work from failure, and preserve the evidence needed for a later investigation.

The proof remains specific. A database reconstruction is not provider recovery; a quota is not financial exposure accounting; a matching statement is not a payout; and a small successful concurrency test is not capacity certification. Those distinctions describe the implemented contracts and identify concrete next requirements if the scope expands.

## 13 Verification history and current evidence

Historical totals below overlap because later runs repeat earlier suites. They must not be added to obtain a project test count. A skipped test has not passed. “Implemented” and “executed in this environment” are separate statuses.

| Milestone and date | Passing coverage | Skipped | Provenance |
| --- | --- | --- | --- |
| 21 on 27 October | 1,138 | 425 | Latest results across broad run and corrected focused follow-up |
| 22 on 27 October | 1,179 | 446 | One successful clean verify |
| 23 on 27 October | 1,216 | 454 | One successful clean verify |
| 24 on 27 October | 1,243 | 454 | Broad run plus 27 overlapping final focused checks |
| 25 on 28 October | 1,269 | 454 | One successful fresh clean verify against final implementation |

M21's earlier broad run had one Windows temporary-log cleanup error after forced provider restart, despite zero assertion failures. The corrected fixture passed its complete focused follow-up, 46 checks. The 1,138 coverage total is not one successful broad execution. M24's final 27 checks are included in its coverage, not added as another 27 unique tests.

The final M25 clean verify completed at 19:19:09 +05:30 on 28 October 2026 in 19:27 minutes. It passed 761 unit/MVC/configuration/CLI, 483 native PostgreSQL, 7 real Kafka and 18 packaged checks, with zero failures/errors. Its 454 Docker-dependent tests skipped. Earlier interrupted/focused attempts are retained privately and excluded from those totals.

M23 separately verified real authenticated Prometheus scraping and local OTLP protobuf transport. Eleven rules and seven promtool temporal scenarios passed outside JUnit totals. M24/M25 used real dump/restore and recovered HTTP. M25's second clean package intentionally skipped tests and reproduced the first tested JAR's exact bytes; it is an artifact comparison, not another test total.

Actual tooling was Windows, Java 21.0.12.1, Maven 3.9.11, PostgreSQL 18.6, native Kafka 3.9.1 and portable Prometheus 3.5.0. Flyway warned that PG18 exceeded its tested support range. This is not proof of the pinned PostgreSQL 17.6/Linux containers. Real Redis Lua/concurrency/expiry, combined Docker startup, Jaeger UI, mixed-version/HA operation, representative sustained load and external/off-site recovery remain unverified here.

The CI workflow passed local actionlint, and Compose configuration/constraints passed static validation. The workflow has not executed or been published. Shell literal-secret/refusal checks and verifier tamper/path cases passed separately. Session-owned test services were stopped; databases, journals, logs and release evidence were retained.

Detailed references: [M21 summary](MILESTONE_21_SUMMARY.md), [M22 summary](MILESTONE_22_SUMMARY.md), [M23 summary](MILESTONE_23_SUMMARY.md), [M24 summary](MILESTONE_24_SUMMARY.md), [M25 summary](MILESTONE_25_SUMMARY.md), and [final validation report](FINAL_VALIDATION_REPORT.md). This reporting session reviews that recorded evidence; it does not rerun application tests or change functionality.

## 14 Source files and operating references

| Area | Important files | Purpose |
| --- | --- | --- |
| Settlement | `SettlementController.java`, `SettlementService.java`, `SettlementRepository.java` | Owned import, immutable comparison and fresh closure |
| Statement protocol | `SettlementWire.java`, `SettlementImportVerifier.java`, provider store/server | Exact signed provider evidence |
| Risk | `PaymentRiskService.java`, `PaymentRiskTransactions.java`, `PaymentRiskRepository.java` | Orchestration and durable intent/decision evidence |
| Redis | `RedisRiskVelocity.java`, `redis/payment-admission.lua`, `RiskProperties.java` | Atomic reservation and bounded policy configuration |
| Telemetry | `FlowObservationAspect.java`, `FlowTelemetry.java`, observability security/config | Private bounded observations |
| Backlogs | `OperationalSnapshotCollector.java`, `OperationalSnapshotRepository.java` | Read-only cached facts and freshness |
| Recovery | `DatabaseRecoveryCli.java`, `DatabaseRecovery.java`, `RecoverySchema.java` | Capture, fingerprints and isolated-target comparison |
| Release | `LocalSandboxCli.java`, `SandboxAcceptanceCli.java`, `SandboxHttp.java` | Bootstrap, complete HTTP exercise and client bounds |
| Deployment | `Dockerfile`, `deployment/compose.yml`, `deployment/entrypoint.sh` | Pinned local runtime and secret loading |
| Verification | `Build-LocalRelease.ps1`, `Verify-LocalRelease.ps1`, CI workflow | Tested artifact packaging and validation recipe |

Use the [settlement runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md), [risk runbook](PAYMENT_RISK_RUNBOOK.md), [observability runbook](OBSERVABILITY_RUNBOOK.md), [database recovery runbook](DATABASE_RECOVERY_RUNBOOK.md), [retention policy](RETENTION_POLICY.md) and [deployment runbook](DEPLOYMENT_RUNBOOK.md) for executable instructions. The [final project map](FINAL_PROJECT_GUIDE.md) connects all 25 milestones. Individual guides name the supporting unit, native, packaged and container suites.

## 15 Completion and the next action

**Completed:** all 25 planned functionality blocks, including independent comparison, admission evidence, private observability, supported logical recovery and verified local release/acceptance.

**Currently possible:** a packaged native sandbox demonstration with real credentials/CSRF, provider uncertainty and signed recovery, full refunds, separate partial accounting, independent statement match, notification failure/retry, bounded concurrent creation, real Kafka projections and retained evidence.

**Next validation action:** run the supplied combined Docker/Redis acceptance on a suitable Docker-enabled machine. Confirm risk and Kafka flags, container permissions/health/lifecycle, and retained-volume reruns. Review the resulting evidence before claiming those environments are validated. No automatic Milestone 26 is added.

**Additional scope if chosen:** live-provider onboarding and payouts; provider partial/fee contracts; authorization release and fee refunds; advanced fraud/grant lifecycle; production TLS/HA and managed secrets; physical/WAL/PITR and coordinated cross-system recovery; off-site custody and physical deletion policy/execution. These require explicit scope and operating decisions. All 25 implemented blocks do not imply these capabilities exist or that a regulated live-payment business is ready.

## 16 Learning handoff

Ask your teacher to trace one uncertain capture from provider commit to local confirmation, then explain which evidence each final milestone adds. Revisit the PostgreSQL commit boundary before studying distributed coordination. Compare an old accepted retry with a new request, a stale observation with a guarded write, and trusted byte agreement with authentication.

Useful review questions are: why can equal totals hide a discrepancy; why should refusal evidence commit before an HTTP exception; why can Redis restart weaken a quota; why must cached gauges expose age; why must dump and inventory share a snapshot; and why can correct services still fail through browser-session state? Answer each using the named class and its durable record, not only a definition.

### Compact glossary

| Term | Meaning in this project |
| --- | --- |
| Reconciliation | Comparison of independent evidence with local confirmed operations |
| Intent fingerprint | Canonical identity of the accepted request body |
| HMAC | Shared-key authentication of exact protocol bytes |
| Admission window | Short-lived Redis capacity period beginning with first admission |
| Terminal decision | Saved ALLOW or DENY with documented replay behavior |
| Cardinality | Number of distinct metric label combinations |
| Trace sampling | Selection of only some requests/spans for export |
| Snapshot freshness | How recently cached source facts were successfully observed |
| Exported snapshot | Database image shared by inventory and pg_dump |
| Logical restore | Reconstructing database objects/data from a logical archive |
| Trusted reference | Separately controlled expected digest used to check bytes |
| Image digest | Fixed identity of container image content |
| Reproducible JAR | Identical bytes from the demonstrated same-input clean builds |
| Acceptance test | Real packaged HTTP exercise across supported boundaries |

### TOPICS TO LEARN FROM THIS SESSION

- Independent reconciliation and exact-byte HMAC
- Immutable evidence and Repeatable Read
- Redis Lua admission and cross-system idempotency
- Transaction propagation and durable refusals
- Metrics tracing cardinality and freshness
- MVCC snapshots and logical restore
- Hash custody and cross-system recovery
- Reproducible builds and container image digests
- CSRF sessions and bounded HTTP clients
- Failure injection CI evidence and validation limits
