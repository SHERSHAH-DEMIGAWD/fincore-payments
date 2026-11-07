# fincore-payments — complete project map through Milestone25

This independent Java21 backend is a learning implementation of the Distributed Payment Processing Platform, using simulated funds. It is separate from `ledger-mvp`. Its purpose is to learn how an accepted payment remains attributable, balanced, repeatable and recoverable when requests, providers and message deliveries fail. The roadmap's twenty-five functionality blocks are implemented; this is not a live-payment launch or a production-readiness certificate. See [actual validation](FINAL_VALIDATION_REPORT.md), [roadmap](ROADMAP.md) and [deployment runbook](DEPLOYMENT_RUNBOOK.md).

## 1. Read the project in layers

| Milestones | Main responsibility | Learning material |
| --- | --- | --- |
| 1–3 | HTTP contracts, payment state, exact retries, balanced immutable journals | [Complete guide1–10](COMPLETE_LEARNING_GUIDE.md) |
| 4–5 | Atomic outbox, at-least-once Kafka, idempotent consumer projection | [Complete guide1–10](COMPLETE_LEARNING_GUIDE.md) |
| 6–10 | Audited event repair, quarantine, reconciliation, shadow rebuild and routing | [Complete guide1–10](COMPLETE_LEARNING_GUIDE.md) |
| 11–15 | Controlled replacement/retirement, evidence planning, preservation and approval lifecycle | [Complete guide11–20](COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.md) |
| 16 | Real merchant credentials, ownership and scoped retries | [M16 guide](MILESTONE_16_LEARNING_GUIDE.md) |
| 17–19 | Durable HTTP provider commands, verified callbacks and outgoing merchant notifications | [M17](MILESTONE_17_LEARNING_GUIDE.md), [M18](MILESTONE_18_LEARNING_GUIDE.md), [M19](MILESTONE_19_LEARNING_GUIDE.md) |
| 20–21 | Partial local principal/fees, independent signed settlement reconciliation | [M20](MILESTONE_20_LEARNING_GUIDE.md), [M21](MILESTONE_21_LEARNING_GUIDE.md) |
| 22–24 | Redis admission/risk, private observability, logical backup/restore and retention decisions | [M22](MILESTONE_22_LEARNING_GUIDE.md), [M23](MILESTONE_23_LEARNING_GUIDE.md), [M24](MILESTONE_24_LEARNING_GUIDE.md) |
| 25 | Verified release, isolated deployment, packaged acceptance and consolidated operating evidence | [M25](MILESTONE_25_LEARNING_GUIDE.md), [deployment runbook](DEPLOYMENT_RUNBOOK.md) |

The two Word volumes are historical M1–10/M11–20 teaching editions. They have not been rewritten to imply later capabilities existed at those dates. This map and continuation guides describe subsequent implementation.

## 2. Authority and derived views

PostgreSQL holds payment intent/state, immutable commands, balanced journals, exact retry responses and operational evidence. A service transaction groups related changes; Flyway owns schema evolution and Hibernate validates mappings. Monetary values use BigDecimal with explicit currency/scale rules. Optimistic versions and row/lease fencing protect the specific concurrency boundaries documented in each guide; they are not universal serial execution.

Kafka transports durable events; it is not the source of financial truth. The consumer inbox and versioned projections tolerate redelivery and expose rebuildable reads. Redis coordinates a soft, short-lived admission quota; loss can replenish it. Provider decisions live in a separate sandbox journal. Signed statements compare that independent evidence with local confirmed operations; comparison does not post money. Metrics/traces help explain stalls but do not replace any of those records.

## 3. Follow one complete payment

```text
Merchant Basic + browser CSRF + Idempotency-Key
→ POST /api/v1/payments
→ PaymentController.createPayment → MerchantPaymentService.createPayment
→ PaymentService.createPayment
→ PostgreSQL [payment + retry response + outbox event]
→ provider-operations HTTP command → sandbox provider journal
→ signed POST /api/v1/provider-webhooks/sandbox
→ confirmed application [state + balanced journals + events]
→ outbox dispatcher → Kafka → inbox/projection
→ signed merchant notification → durable receiver receipt
→ signed statement import → independent reconciliation report
```

The exact controller/service method names for creation are verified in source; provider/callback/accounting flows have different command/state boundaries. A network timeout after provider acceptance is UNKNOWN. Retrying the same durable provider request and processing a duplicate signed callback must converge without a second financial effect. M25 exercises this through the packaged API and real local broker rather than direct service calls.

## 4. Concepts, alternatives and costs

| Chosen approach | Why it fits this project | Alternative and tradeoff |
| --- | --- | --- |
| Controller/service/repository with DTOs | Separates HTTP contract, transactions and persistence | A single controller is shorter initially but couples ownership, accounting and database concerns |
| BigDecimal + explicit currency rules | Avoids binary floating-point money rounding | Integer minor units are also valid with a complete currency/rounding model; double is unsuitable here |
| Flyway + Hibernate validation | Versioned reviewable constraints and compatible migrations | Auto-update lacks a controlled historical migration record; handwritten JDBC everywhere costs mapping effort |
| Exact stored retries scoped by merchant | Repeats the accepted response and rejects changed intent | In-memory maps lose history at restart; global keys collide across tenants |
| Balanced immutable journals | Supports accounting checks and auditable effects | Mutable balance alone loses the explanation of how it changed; journals cost rows and reconciliation work |
| Transactional outbox and idempotent inbox | Bridges a PostgreSQL transaction and at-least-once delivery | Direct publish can lose events between commit and send; XA adds coordination and operational complexity |
| Explicit UNKNOWN and fenced command recovery | Represents ambiguous external acceptance honestly | Treating every timeout as failure risks duplicate effects; fencing adds states and repair procedures |
| HMAC over exact bytes, freshness and receipts | Authenticates bounded local callbacks/statements and deduplicates delivery | TLS alone does not identify replayed application events; HMAC still requires private key custody/rotation |
| AES-GCM protected webhook signing keys | Keeps retained per-subscription key material encrypted in PostgreSQL | Plain stored keys expose all destinations on DB compromise; master-key custody remains external |
| Generation-based projection repair | Isolates uncertain replay/rebuild work and controls routing changes | In-place edits are simpler but can alter serving reads before validation |
| Redis Lua admission + PostgreSQL intent evidence | Atomic shared bounded quota with durable attribution | Per-process counters split across instances; a hard financial limit would require stronger durable accounting |
| Private observer role + fixed metrics | Gives diagnosis without payment/recovery authority or sensitive labels | Public/debug endpoints enlarge exposure; bounded labels sacrifice per-payment metric detail |
| Consistent logical snapshot + isolated restore | Tests source/dump agreement and recovered behavior | Physical/WAL/PITR and cross-system recovery cover different goals and are not implemented here |
| Digest-pinned runtime + verified prebuilt JAR | Binds deployment to the artifact actually tested | Mutable tags drift; a separate in-image rebuild can differ from the tested JAR; pins need explicit refresh |
| Shared API network namespace for local adapters | Preserves existing literal-loopback contracts in Compose | Relaxing destination allowlists expands network reach; shared namespaces add lifecycle coupling |

These choices fit the bounded learning scope. Alternatives are not universally worse. For example, a managed secret store and independently secured provider integration would be preferable for a live environment; they require deployment authority and operating controls outside this demo.

## 5. What Milestone25 changes in the whole system

Previous milestones established local invariants independently. M25 joins them in a repeatable packaged demonstration: create→provider uncertainty→verified recovery→balanced journals→notification delivery/recovery→statement matching→Kafka projection. A second payment demonstrates partial local captures/refunds and retained fees without mixing that protocol with full provider operations. Same-key races verify one accepted creation; distinct creations exercise bounded concurrent work.

Generated credentials, bootstrap idempotency, observer-only readiness and an allowlisted release bundle make the demonstration reproducible without putting private values in arguments or release artifacts. The release manifest reports the test evidence that actually ran. A fixed JAR output timestamp supports same-toolchain byte reproducibility; generated manifests, test timings, random demo identities and runtime reports intentionally vary.

Browser state was a material integration lesson: sharing cookies across identities/concurrent workers caused CSRF failures in early acceptance runs. The final client separates browser sessions and cookie-free machine traffic, retrieving a fresh masked token before writes. It preserves the API's security behavior. A bounded client also limits the complete response, since receiving headers does not prove the body will finish.

## 6. Completion and limits

| Area | Implemented | Actual local verification boundary |
| --- | --- | --- |
| Financial/domain/security flows | Yes | Unit, native PostgreSQL and packaged API drills; see final report for counts |
| Kafka delivery/replay/projection | Yes | Real local Kafka3.9.1 tests and packaged acceptance |
| Risk controls | Yes | Unit/native repository and stubbed boundary tests; real Redis/container checks remain unexecuted on this host |
| Observability | Yes | Native Prometheus/OTLP checks; Docker/Jaeger UI remains unexecuted |
| Database recovery | Yes | Same-host logical dump/restore and recovered API checks; no external/off-site recovery claim |
| M25 release/deployment | Yes | Release hashes, native packaged acceptance, recipe parsing and tool checks recorded separately from an unexecuted container run |

There are **zero remaining planned functionality milestones** in the agreed1–25 roadmap. The next action is to execute the supplied Docker/Redis combined acceptance on a Docker-enabled machine, review the evidence and decide whether to extend the scope. That validation gap is not silently renamed Milestone26. Bank payouts, live-provider onboarding, provider partial commands, fee tariffs/refunds, authorization release, advanced fraud, TLS/HA, managed secrets, off-site/PITR/cross-system cutover and physical deletion remain additional requirements. Completing twenty-five blocks is not evidence that those features exist.

## 7. How to study and operate it

Start with the authority model, then follow an exact retry and a lost provider reply. Read the service transaction and corresponding database constraints together. Run the [deployment acceptance](DEPLOYMENT_RUNBOOK.md), inspect its safe JSON report, then read the [observability](OBSERVABILITY_RUNBOOK.md), [webhook](MERCHANT_WEBHOOK_RUNBOOK.md), [settlement](SETTLEMENT_RECONCILIATION_RUNBOOK.md) and [recovery](DATABASE_RECOVERY_RUNBOOK.md) runbooks when investigating a failure. Preserve originals and fix evidence discrepancies rather than deleting them. Historical summaries record what was verified at each milestone; the [final validation report](FINAL_VALIDATION_REPORT.md) records the latest combined evidence.

## Topics to learn from the completed project

- Spring request lifecycle, DTOs and validation
- Service transactions, JPA and PostgreSQL constraints
- Idempotency, optimistic locking and lease fencing
- Double-entry accounting and immutable commands
- Outbox/inbox, Kafka ordering, redelivery and projections
- Reconciliation, rebuild generations and controlled recovery
- Authentication, tenant isolation and browser CSRF sessions
- Provider uncertainty, HMAC, encryption and key custody
- Redis Lua, soft quotas and durable risk attribution
- Metrics, traces, private readiness and alert freshness
- MVCC snapshots, logical backup and restore verification
- Artifact hashes, image digests, non-root containers and CI evidence
