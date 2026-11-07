# fincore-payments: the complete learning guide

**Latest continuation (2026-10-28):** [Milestone25](MILESTONE_25_LEARNING_GUIDE.md) adds verified release/deployment inputs and packaged end-to-end sandbox acceptance. Use the [final project map](FINAL_PROJECT_GUIDE.md), [deployment runbook](DEPLOYMENT_RUNBOOK.md), [final validation report](FINAL_VALIDATION_REPORT.md) and [summary](MILESTONE_25_SUMMARY.md). All25 planned functionality blocks are implemented; Docker/real Redis combined execution is the next environment-validation action. Older next-step notes below and historical Word volumes retain their dated scope.

**Latest continuation (2026-10-27):** [Milestone 24 — logical database recovery](MILESTONE_24_LEARNING_GUIDE.md) adds consistent snapshot-backed archives, read-only isolated-restore verification and actual recovered API checks. Use the [runbook](DATABASE_RECOVERY_RUNBOOK.md), [retention decision](RETENTION_POLICY.md) and [summary](MILESTONE_24_SUMMARY.md). M25 deployment/final validation is the one remaining planned block. Older next-step notes below and Word volumes retain their historical scope.

**Latest continuation (2026-10-27):** [Milestone 23 — payment observability](MILESTONE_23_LEARNING_GUIDE.md) adds private observer credentials, bounded metrics/traces, sampled source backlogs and freshness-aware alerts. Read its [runbook](OBSERVABILITY_RUNBOOK.md) and [verification summary](MILESTONE_23_SUMMARY.md). M24 restore/operational closure is next; two planned blocks remain through M25. Older next-step statements below are historical, and the Word volumes retain their original scope.

**Latest continuation (2026-10-27):** [Milestone 22 — Redis admission and risk rules](MILESTONE_22_LEARNING_GUIDE.md) follows the M21 settlement guide. It adds deterministic creation screening, bounded Redis quotas and durable decision/retry evidence. Use the [runbook](PAYMENT_RISK_RUNBOOK.md), [verification summary](MILESTONE_22_SUMMARY.md) and [current roadmap](ROADMAP.md); older next-step notes below are historical.

**Latest continuation (2026-10-27):** after the M11–20 volume, read [Milestone 21 — independent settlement reconciliation](MILESTONE_21_LEARNING_GUIDE.md), its [runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md) and [verification summary](MILESTONE_21_SUMMARY.md). Signed provider-journal statements, owned immutable reports and proof-based closure are implemented; M22–25 remain proposed. Older next-step statements below retain their historical scope.

**Consolidated continuation (2026-10-27):** Read the [Milestones 11–20 learning guide](COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.md) or its [Word edition](FINCORE_PAYMENTS_COMPLETE_LEARNING_GUIDE_MILESTONES_11_TO_20.docx) after this volume. It explains the implemented platform through M20, connects the concepts and tradeoffs, and identifies M21 settlement/reconciliation as proposed next work. The continuation notes below preserve the earlier release history; their next-step statements describe those earlier boundaries.

**Current continuation (2026-10-25):** [Milestone 18](MILESTONE_18_LEARNING_GUIDE.md) adds verified incoming provider callbacks, exact durable receipts, duplicate/out-of-order handling and atomic application through the existing financial path. The [webhook runbook](PROVIDER_WEBHOOK_RUNBOOK.md) covers optional sandbox delivery/redelivery. Outgoing merchant notifications are next; earlier no-callback descriptions retain their historical scope.

**Current continuation (2026-10-25):** [Milestone 17](MILESTONE_17_LEARNING_GUIDE.md) connects owned payments to a separate local HTTP sandbox with durable command identity, uncertainty/retry handling, lease fencing and guarded financial application. Use the [provider runbook](PROVIDER_INTEGRATION_RUNBOOK.md). Earlier manual-transition examples apply only to unbound payments; provider-managed payments require provider commands. No real funds or incoming callbacks are included; Milestone 18 is next.

**Current continuation (2026-10-25):** [Milestone 16](MILESTONE_16_LEARNING_GUIDE.md) adds merchant authentication, immutable payment ownership, tenant-scoped retries and financial/event reads. Payment HTTP endpoints now require merchant credentials and POST CSRF; older public-API descriptions are historical. Use the [merchant runbook](MERCHANT_ACCESS_RUNBOOK.md) and [bounded completion roadmap](ROADMAP.md).

**Current continuation (2026-10-25):** [Milestone 15](MILESTONE_15_LEARNING_GUIDE.md) adds fixed approval expiry, immutable authenticated withdrawal and independent downloaded-file verification. Follow the [lifecycle/verification runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md). Earlier milestone descriptions retain their historical scope; deletion remains unavailable.

**Current continuation (2026-10-24):** [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md) adds verified full-body preservation exports and authenticated preservation-review approval. Deletion remains unavailable. See the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md); earlier milestone scope/next-work descriptions below are historical.

**Current continuation (2026-10-24):** [Milestone 13](MILESTONE_13_LEARNING_GUIDE.md) adds audited bounded purge plans and fresh review. Plans retain all data and grant no deletion permission; see the [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). Earlier milestone descriptions below retain their historical scope.

**Scope:** Milestones 1–10 snapshot. **Last updated:** 2026-10-24. The Milestone 10 implementation evidence remains dated 2026-10-22. **Current continuations:** [Milestone 11 target replacement and retirement](MILESTONE_11_LEARNING_GUIDE.md) and [Milestone 12 retained evidence review and operating limits](MILESTONE_12_LEARNING_GUIDE.md), [Milestone 13 purge planning](MILESTONE_13_LEARNING_GUIDE.md) and [Milestone 14 preservation and review approval](MILESTONE_14_LEARNING_GUIDE.md) are implemented. Their summaries and the current [routing runbook](PROJECTION_ROUTING_RUNBOOK.md) supersede the lifetime-shadow restriction and proposed-next-work statements below. The Word edition remains the requested Milestones 1–10 snapshot.

This is the main reference for understanding what we built, how it works, why the choices fit, what they cost, and what should come next. It combines the session records with the current code. The [session log](SESSION_LOG.md) preserves the historical reports; the [README](../README.md) contains setup commands.

Milestones 6–10 now have full teaching chapters in the same format as the earlier foundations: the problem, each concept's role in this code, a concrete failure case, the chosen approach and its alternatives, the cost retained, and the actual request or command flow. Read this file as one cumulative guide; individual milestone guides provide additional examples and operator runbooks provide commands.

Within this snapshot, implemented behavior is described as **current** as of Milestone 10. Future work is explicitly marked **proposed** relative to that release; use the Milestone 11–15 continuations for current lifecycle, operating, planning, preservation and approval-validity behavior. Alternative comparisons explain engineering fit; they are not claims that every alternative was benchmarked or that one technology is universally better.

## Contents

1. [Purpose and scope](#1-purpose-and-scope)
2. [How the ten milestones connect](#2-how-the-ten-milestones-connect)
3. [Architecture and commit boundaries](#3-architecture-and-commit-boundaries)
4. [Application foundations](#4-application-foundations)
5. [Persistence, transactions, and concurrency](#5-persistence-transactions-and-concurrency)
6. [Idempotent payment creation](#6-idempotent-payment-creation)
7. [Double-entry ledger](#7-double-entry-ledger)
8. [Transactional outbox](#8-transactional-outbox)
9. [Kafka and consumer processing](#9-kafka-and-consumer-processing)
10. [Identities and versions](#10-identities-and-versions)
11. [A complete payment story and its failure cases](#11-a-complete-payment-story-and-its-failure-cases)
12. [Why the recent developments matter](#12-why-the-recent-developments-matter)
13. [Testing and current readiness](#13-testing-and-current-readiness)
14. [Milestone 6: event operations and audited outbox recovery](#14-milestone-6-event-operations-and-audited-outbox-recovery)
15. [Milestone 7: consumer quarantine, original replay, and known gaps](#15-milestone-7-consumer-quarantine-original-replay-and-known-gaps)
16. [Milestone 8: projection reconciliation and source coverage](#16-milestone-8-projection-reconciliation-and-source-coverage)
17. [Milestone 9: isolated shadow rebuilding and catch-up generations](#17-milestone-9-isolated-shadow-rebuilding-and-catch-up-generations)
18. [Milestone 10: authenticated projection promotion and rollback](#18-milestone-10-authenticated-projection-promotion-and-rollback)
19. [The next implementation](#19-the-next-implementation)
20. [Code and API map](#20-code-and-api-map)
21. [Study path and reference material](#21-study-path-and-reference-material)

If you have ten minutes, read sections 1–3, 11–12, and 19. To continue after the original payment/outbox/Kafka guide, read sections 14–18 in order. For a deeper understanding, work through sections 4–10 and 14–18 with the linked source files open. Section 21 provides questions to discuss with your teacher.

## 1. Purpose and scope

The long-term objective is a **Distributed Payment Processing Platform**. The present project is an independent Java backend for learning the correctness problems behind that objective: accepting requests safely, preserving accounting facts, handling concurrency, and communicating across systems that can fail independently.

A useful question drives the whole project: **what should remain true when the same request arrives twice, two workers race, or a process stops between two commits?** Each milestone addresses another part of that question.

The current application records a payment lifecycle and ledger obligations. An `authorize` or `capture` call changes this model; it does not call a card network, acquire money, or settle funds. There is no payment-provider integration, merchant authentication, wallet system, or production deployment. The ledger's clearing and payable accounts are pooled learning accounts per currency, not balances fetched from a bank.

Java/Spring Boot is the chosen learning stack. Keeping this project independent lets its domain, schema, migrations, and dependencies develop coherently. The guide covers only `fincore-payments`.

## 2. How the ten milestones connect

| Session | Problem introduced | What was implemented | Why the next layer depends on it |
| --- | --- | --- | --- |
| 1: payment foundation | Which operations are valid, and who wins concurrent changes? | REST endpoints, domain state machine, validation, transactions, optimistic locking, PostgreSQL/Flyway | Later features need a trustworthy payment state. |
| 2: creation idempotency | The server accepted a create request, but the caller lost the response. | Durable request key, fingerprint, original response snapshot, safe concurrent claims | Network retries must not manufacture extra payments. |
| 3: ledger | A status such as CAPTURED does not explain the accounting effect. | Balanced immutable capture journals and refund reversals; balance/history reads | Accounting history must agree with successful payment operations. |
| 4: outbox | A payment committed, but notification could disappear on a crash. | Event saved with the payment/ledger transaction; ordered background delivery, retries, local sink | Delivery intent becomes durable before crossing a system boundary. |
| 5: Kafka and projection | Broker/database commits are separate, so events can repeat. | Kafka publication, durable consumer receipts, version-aware payment projection | Other processing can consume facts asynchronously without repeating financial writes. |
| 6: event operations | Failed delivery needs diagnosis and a safe, auditable recovery path. | Local operator CLI, backlog/blocker views, lag states, consumer failure evidence, generation-checked DEAD requeue | Recovery becomes an explicit operation; controlled consumer repair can build on preserved evidence. |
| 7: controlled consumer recovery | A permanently failing record can block healthy records on its partition. | Explicit audited quarantine, visible projection gaps, original-only replay, shared position locks and atomic success/audit | Reconciliation can distinguish accepted gaps from progress and define coverage before correcting/rebuilding. |
| 8: projection reconciliation | Progress indicators and a latest version alone do not establish data agreement or history coverage. | Read-only source/outbox/receipt/projection comparison, snapshot consistency, explicit pages, gap context and replay-input assessment | Rebuilds need reviewed source coverage, logical identities and an isolated target before any promotion. |
| 9: isolated shadow rebuild | Existing receipts prevent ordinary replay from rebuilding a lost read model. | Generated shadow identity, frozen manifests, atomic resumable batches, audited catch-up generations and current-source validation | Promotion requires explicit freshness fencing, reader routing, authenticated ownership and rollback semantics. |
| 10: authenticated promotion and rollback | A validated shadow can become stale before cutover or stop advancing after a pointer switch. | Fenced fresh checks, authenticated actor, immutable route decisions, revision/idempotent requests, one-statement reads and atomic baseline/shadow fanout | Replacement/retirement needs explicit ownership transfer, coverage and retention rather than accumulating live targets. |

These protections accumulate. Kafka does not replace the database transaction, and consumer deduplication does not replace request idempotency. They protect different boundaries.

## 3. Architecture and commit boundaries

The current deployment is **one Spring Boot application**, PostgreSQL, and an optional Kafka broker. Producer and consumer are separate logical components inside the application; they have not been deployed as separate microservices. Source and consumer tables currently use the same PostgreSQL database, but consumer tables do not reference source payments/outbox through foreign keys.

```text
Client
  │ HTTP
  ▼
PaymentController → PaymentService → Payment domain methods
                              │
                              ▼
                 PostgreSQL source transaction
                 ├─ idempotency_records, on creation
                 ├─ payments
                 ├─ ledger journals, on capture/refund
                 └─ payment_outbox
                              │ committed event
                              ▼
                 OutboxDispatcher → OutboxDeliveryWorker
                              │ selected transport
                 ┌────────────┴──────────────┐
                 ▼                          ▼
          LOCAL database sink       KafkaPaymentEventPublisher
          local_payment_event_inbox          │ broker acknowledgement
          no payment projection             ▼
                                      Kafka topic
                                            │
                                            ▼
                               KafkaPaymentEventListener
                                            │
                                            ▼
                                  ConsumerRecordProcessor
                                  → PaymentEventConsumer
                                            │
                                            ▼
                                PostgreSQL consumer transaction
                                ├─ consumed_payment_events
                                ├─ payment_event_projections
                                └─ source-position success marker
                                   + failure resolution COMMIT
                                            │
                                            ▼
                                    Kafka offset commit
```

After the first authenticated promotion, the consumer transaction maintains both baseline and the one enrolled shadow. The read/decision paths are:

```text
GET /api/v1/payments/{id}/projection
  → PaymentProjectionController.getProjection
  → PaymentEventConsumer.getProjection
  → PaymentEventConsumerRepository.findRoutedProjection
  → one committed route + projection statement snapshot

POST /api/v1/operations/projection-routing/promote or /rollback
  → scoped Spring Security authentication + role + CSRF
  → ProjectionRoutingController → ProjectionRoutingService
  → exclusive fence + fresh data/gap checks
  → route revision + immutable authenticated decision COMMIT
```

Route switching remains local to this co-located database/read model; an independently deployed projection would need another ownership and cutover protocol.

### Four facts stored for different reasons

| Storage | Question it answers |
| --- | --- |
| `payments` | What is the authoritative current payment state? |
| `ledger_transactions` and `ledger_entries` | What accounting postings were recorded? |
| `payment_outbox` | Which payment facts must be delivered, and what is their delivery status? |
| `payment_event_projections` | What latest snapshot has this consumer successfully processed? |

A projection can be behind the payment. A DELIVERED outbox row can precede consumer completion. Neither situation alone means the source payment transaction failed.

### Atomicity stops at a boundary

| Boundary | What commits together | What is separate |
| --- | --- | --- |
| Payment operation | Payment, optional ledger posting, outbox event; creation also includes its idempotency claim | Kafka publication |
| LOCAL dispatch | Local inbox receipt and outbox DELIVERED metadata | No Kafka consumer runs on this path |
| Kafka dispatch | Outbox DELIVERED metadata, after acknowledged send | Kafka accepted the record in a separate system |
| Kafka consumer | Receipt, projection, source-position success marker and any failure resolution | Kafka offset commit follows database commit; durable quarantine permits acknowledgement without manufacturing success |
| Failed-consumer diagnostics | Original evidence, failure attempts and timestamps | REQUIRES_NEW after failed processing rolls back |
| Operator requeue | DEAD-to-PENDING reset, recovery generation, immutable audit | Does not publish, mutate payment state, or post accounting |
| Operator quarantine/replay | Quarantine audit preserves an open gap; replay commits receipt/projection, success markers and immutable replay audit | Kafka acknowledgement remains separate; no replacement event or financial writes |
| Shadow planning/catch-up | Run/generation identity, frozen manifest and captured coverage/gap context | Source may advance after this snapshot; no live reader switch |
| Shadow batch | Generated-identity receipt/projection, per-item checkpoint and generation state | Other approved batches commit independently; live data is preserved |
| Route promotion/rollback | Fresh fenced comparisons, first enrollment, pointer revision and immutable authenticated audit | A subsequent source write still reaches projections asynchronously; no Kafka offset or payment/ledger mutation |
| Consumer after enrollment | Baseline and one warm shadow receipts/projections, target agreement and normal success/failure resolution | Both remain asynchronous from source writes; rollback keeps enrollment and fanout |

There is no single transaction spanning PostgreSQL and Kafka. The design makes the uncertain windows retryable and gives retries durable identities.

## 4. Application foundations

### 4.1 Java, Spring Boot, and dependency injection

Java 21 provides the language/runtime; Spring Boot 3.5 configures the application and integrates the web server, persistence, validation, and Kafka libraries. Maven resolves dependencies and builds the executable JAR. The Maven wrapper pins the build-tool version; Spring Boot dependency management supplies compatible library versions rather than individually choosing every transitive version.

Spring creates managed objects, called **beans**, and passes their collaborators through constructors. For example, `PaymentService` receives its repositories, `LedgerService`, and `OutboxService`. The same structure makes tests able to supply mocks. Manually constructing a transactional service with `new` would bypass Spring's transaction proxy.

Java records hold DTOs and event/journal data. Enums constrain named states, operations, and entry sides. Immutable records and copied lists reduce accidental mutation; they do not by themselves make a database history immutable.

| Annotation family | Where to recognize its purpose |
| --- | --- |
| `@RestController`, `@Service`, `@Repository` | Managed components with HTTP, business, and persistence roles |
| `@Entity`, `@Table`, `@Id`, `@Column` | Map the payment object and its fields to relational storage |
| `@Configuration`, `@Bean`, `@ConditionalOnProperty` | Construct infrastructure and select LOCAL or Kafka components |
| `@ConfigurationProperties`, `@Validated` | Bind typed settings and reject invalid bounds at startup |
| `@EnableScheduling`, `@Scheduled` | Activate periodic dispatch only when its configured scheduling mode is enabled |
| `@Transactional`, `@Version`, `@KafkaListener` | Establish the transaction, concurrency, and message-receiving boundaries explained below |

**Alternative and cost:** a smaller framework or manual wiring could implement the same behavior. Spring was selected for the Java backend learning objective and integrated tooling. The cost is learning bean lifecycle, proxies, auto-configuration, and transaction behavior rather than treating annotations as magic.

### 4.2 Controller, service, domain, repository, DTO

| Part | Responsibility in this project | Example |
| --- | --- | --- |
| Controller | Translate HTTP input/output and status codes | `PaymentController.createPayment` |
| Service | Coordinate a business operation and its transaction | `PaymentService.capturePayment` |
| Domain | Enforce allowed payment transitions and posting rules | `Payment.capture`, `LedgerTransaction.capture` |
| Repository | Read/write storage | `PaymentRepository`, `LedgerRepository` |
| DTO | Define API data independently of persistence | `CreatePaymentRequest`, `PaymentResponse` |

Putting everything in a controller would make transaction boundaries and reuse harder to inspect. Returning JPA entities directly would tie the API to persistence fields and entity behavior. DTOs make the contract deliberate: for example, the public payment response does not expose the JPA version field.

This layering costs extra classes. It earns its place because capture now coordinates domain state, optimistic locking, accounting, and event recording in one understandable service operation.

### 4.3 REST, validation, and errors

`@RestController` and mapping annotations expose JSON endpoints. `@RequestBody` binds JSON; `@Valid` and Bean Validation constraints reject bad request fields. `GlobalExceptionHandler` translates exceptions into `ApiError`, whose fields include timestamp, status, error code, message, path, and optional field errors.

| Result | Meaning here |
| --- | --- |
| 201 with Location | A new idempotency key created a payment. |
| 200 | Successful read/transition, or a creation replay. |
| 400 | Missing/invalid key, malformed JSON, invalid fields, or invalid UUID. |
| 404 | Payment absent, or consumer projection not available; error codes distinguish them. |
| 409 | Illegal transition, conflicting key reuse, concurrent update, or ledger-state conflict. |
| 500 | Unexpected failure with a sanitized public message. |

Request validation gives useful early feedback. Domain checks also protect service use outside HTTP. Database constraints protect their own invariants even when ordinary SQL is used. These are complementary checks, not proof that every domain rule exists at every layer.

**Alternative:** validating only in the controller makes internal callers easier to misuse; relying only on SQL makes simple input errors harder to explain. Repeating every rule everywhere would also add maintenance cost, so the boundaries have distinct jobs.

### 4.4 The payment state machine

```text
CREATED ──authorize──> AUTHORIZED ──capture──> CAPTURED ──refund──> REFUNDED
   │                      │
   └───────fail────────────┴────────────────> FAILED
```

`Payment.authorize`, `capture`, `fail`, and `refund` enforce these transitions. FAILED and REFUNDED are terminal. Capture requires AUTHORIZED; refund requires CAPTURED. A repeated capture does not silently succeed, and creation idempotency does not make every lifecycle endpoint replayable.

An enum alone only limits available status names. Domain methods encode which changes are legal. JPA stores the state as its name with `EnumType.STRING`; an enum's numeric position would be fragile when declarations are reordered. The database checks permitted status values and financial consistency, but it does not independently enforce the complete transition graph for arbitrary raw SQL writers.

**Alternative:** letting a caller submit any status would expose invalid transitions. A generic workflow engine could help if workflows became configurable and much larger; it would add machinery for a small fixed lifecycle today.

### 4.5 Exact money, identifiers, and time

Amounts use Java `BigDecimal` and PostgreSQL `NUMERIC(19,2)`: up to 17 integer digits and two decimal places. The domain normalizes scale with `RoundingMode.UNNECESSARY`, so an amount needing rounding fails instead of being silently changed. Construct decimal examples from text, such as `new BigDecimal("10.01")`; routing through binary floating point can already lose precision. See [Java BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html) and [PostgreSQL numeric types](https://www.postgresql.org/docs/17/datatype-numeric.html).

The Kafka listener binds the event from the original JSON into `BigDecimal`. A default floating-point JSON tree can otherwise round a large amount before validation. Regression cases include `9007199254740992.01` and `99999999999999999.99`.

| Choice | Why it fits | Alternative and tradeoff |
| --- | --- | --- |
| Decimal money | Matches the API/storage's explicit two-decimal model | Integer minor units are also sound, but need a currency/exponent convention; `double` is unsuitable for exact cent equality. |
| UUID payment/event/journal IDs | Generate identities before inserts and preserve them across retries | Database sequences are valid and compact; they change how an ID is obtained before the complete creation claim. |
| `Instant`, UTC JDBC handling, timezone-aware database timestamps | Represent instants without depending on a machine's local clock display | Local date/time needs an explicit timezone interpretation. |

Currency validation currently checks three uppercase letters, not a currency registry. All supported inputs use this learning model's two decimals; actual currency-specific exponents remain future work. A timestamp records when something happened; it is not the concurrency token or a reliable substitute for payment version order.

## 5. Persistence, transactions, and concurrency

### 5.1 PostgreSQL, JPA/Hibernate, and JDBC

PostgreSQL stores durable relational data and arbitrates uniqueness and concurrent updates. JPA is the persistence API; Hibernate implements it. `PaymentRepository` uses Spring Data JPA for payment entities. Hibernate tracks a managed payment and writes changed fields through **dirty checking**.

`JdbcTemplate` is used where explicit SQL makes the required behavior clearer: idempotency claims, ledger inserts/aggregates, outbox row locking, and consumer upserts. JPA and JDBC join the same configured datasource transaction in these services. This is not a distributed transaction between two databases.

**Why mix them?** JPA reduces ordinary entity plumbing, while JDBC exposes exact affected-row counts and PostgreSQL conflict/locking semantics. An all-JPA implementation could use native queries; all-JDBC could work too. The cost of mixing is having to understand entity flush timing and the shared transaction rather than assuming any repository call commits on its own.

PostgreSQL fits the journal's relational constraints and the SQL concurrency techniques already used. Document databases can have transactions too; choosing one would require expressing and testing these invariants in that model. There is no benchmark here claiming SQL is universally faster.

### 5.2 Flyway and schema evolution

Flyway runs versioned SQL migrations. Hibernate uses `ddl-auto: validate` to check mappings rather than mutate the schema automatically. The current chain is:

| Migration | Adds |
| --- | --- |
| V1 | Payments, checks, and indexes |
| V2 | Idempotency records and deferred payment foreign key |
| V3 | Ledger accounts, journals, entries, integrity checks, legacy backfill |
| V4 | Immutable source event envelopes, delivery metadata, local inbox |
| V5 | Consumer receipts and latest-payment projections |
| V6 | Retry cycles/generations, immutable recovery audits, enforced requeue conditions |
| V7 | Consumer failure evidence and durable source-position success markers |
| V8 | Immutable consumer quarantine/replay decisions and enforced replay/success consistency |
| V9 | Shadow rebuild runs, immutable input generations, manifest checkpoints and consistency checks |
| V10 | One-time live shadow enrollment, route revisions/audits, deferred agreement and enrolled-rebuild freeze guards |

Older migrations were preserved as later milestones arrived. A fresh database and an upgraded database should reach the intended schema through the same version history. Automatic ORM schema updates would be convenient initially, but would not express this project's SQL triggers and deliberate data backfill with the same reviewable history.

Indexes support lookups and constraints. The merchant-reference index is deliberately non-unique: a reference is not currently a merchant-scoped identity or an idempotency key. Unique constraints arbitrate identity claims; a pre-insert Java lookup cannot substitute for them.

### 5.3 Transaction, flush, commit, rollback

`@Transactional` wraps a service operation. In this application, an escaping runtime failure rolls its database changes back. **Flush sends SQL; commit finishes the transaction.** A successful flush can detect a stale version and obtain an assigned version, while a later ledger constraint or outbox failure can still cause rollback.

For capture, the order is:

```text
load managed Payment → Payment.capture()
→ paymentRepository.flush()  [version-checked SQL, still uncommitted]
→ LedgerService.postCapture()
→ OutboxService.record()
→ service proxy commits  [deferred checks must also succeed]
```

Spring's usual proxy mode intercepts calls through the managed proxy. The listener's proxied ConsumerRecordProcessor.process boundary therefore matters: it returns after receipt, projection and success-resolution work commits. PaymentEventConsumer.consume joins that transaction; its return to the processor is not a separate commit. A method calling another method on itself does not automatically establish a new proxy-intercepted boundary. The private `PaymentService.transition` helper works inside the transaction already started by its public entry method. See [Spring transaction annotation behavior](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html).

| Setting used | Purpose |
| --- | --- |
| Default REQUIRED | Join or start the payment operation's transaction. |
| MANDATORY | Ledger posting and source event recording must already be inside a transaction. |
| REQUIRES_NEW | Delivery and diagnostics commit independently; reconciliation, rebuild operations and routing own their transaction/snapshot boundaries. |
| READ COMMITTED | Used for creation claims, live consumption, bounded rebuild steps and fenced routing decisions. |
| REPEATABLE READ | Keeps reconciliation and rebuild planning/comparison queries within one observation snapshot; planning may need a whole-transaction retry. |
| `readOnly = true` | Declares read intent for query operations; it is not an authorization mechanism. |

`open-in-view: false` keeps persistence work inside the service boundary instead of relying on an entity session during HTTP response rendering.

### 5.4 Optimistic locking

`Payment.version` has `@Version`. Conceptually, Hibernate updates a row with a condition like `WHERE id = ? AND version = oldVersion` and advances the version. Two requests that loaded the same version cannot both successfully commit a conflicting managed update.

If both loaded AUTHORIZED, one capture wins and the stale writer receives a concurrent-update conflict. If the second request loads only after the first committed CAPTURED, the domain rejects its capture as an illegal transition. Both paths can produce HTTP 409 for different reasons.

| Alternative | Why it was not the default payment strategy | When it could fit |
| --- | --- | --- |
| Pessimistic `SELECT FOR UPDATE` | Holds ownership from the read through the business operation | High-contention work where deliberate serialization is preferable |
| SERIALIZABLE isolation | Requires handling serialization aborts and retries; does not identify duplicate client intent | More complex multi-row invariants that need that isolation |
| Java `synchronized` | Coordinates only callers sharing that JVM monitor | Small process-local coordination, not replica-wide payment correctness |

Optimistic locking is not lock-free: SQL updates and uniqueness checks still acquire database locks. `@Version` also does not protect an arbitrary SQL writer that bypasses its version condition. Read [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html) for the underlying locks.

## 6. Idempotent payment creation

### 6.1 The lost-response problem

Suppose creation commits, but the network drops the response. The client cannot tell whether the server accepted it. A new unrestricted POST could create a second payment. **Idempotency lets the caller repeat the same logical creation request under the same key and receive the saved creation result.**

The header is `Idempotency-Key`, containing 1–255 visible ASCII characters without whitespace. Keys are exact and case-sensitive; V2 gives the primary-key column `C` collation. The caller must reuse the original key for a retry; a fresh key expresses another creation attempt.

### 6.2 Fingerprint and snapshot

`PaymentRequestFingerprint.of` computes SHA-256 over length-prefixed UTF-8 fields: operation marker `create-payment:v1`, exact merchant reference, normalized decimal value, and uppercase currency. Decimal trailing zeros and JSON field order do not change the logical fingerprint. HTTP validation still restricts the accepted input; fingerprint normalization is not permission to send lowercase currency or extra decimal precision.

Length prefixes preserve field boundaries. SHA-256 provides a compact comparison value, not encryption, request authentication, or proof of merchant identity.

The idempotency record stores the **original `PaymentResponse` snapshot**. Replaying creation after capture or refund returns that creation response, not the latest payment status. Use the normal payment GET for current state. This prevents a retry's result from changing with later operations.

### 6.3 How concurrent callers agree

1. `PaymentService.createPayment` validates input and constructs a proposed payment and complete creation snapshot.
2. `IdempotencyRepository.claim` performs `INSERT ... ON CONFLICT DO NOTHING` against the unique key.
3. Only an insert affecting one row owns creation. It saves and flushes the payment and records its source event, in the same transaction.
4. A caller contending for the same key waits for the competing claim to resolve. A separate READ COMMITTED SELECT reads its committed fingerprint and snapshot.
5. Same fingerprint returns the saved result with 200; changed fingerprint returns 409. A winning new creation returns 201 and Location.
6. If the competing owner rolls back, it leaves no committed claim/payment/event; another claimant can win.

The claim references a payment before that payment is inserted. V2's `DEFERRABLE INITIALLY DEFERRED` foreign key permits that temporary order inside one transaction, but checks the relationship at commit. All claim fields are complete; no pending-record completion workflow is needed.

READ COMMITTED gives the later SELECT a new statement snapshot. Combining the insert and fallback read into one same-snapshot statement, or casually changing isolation, can change this race behavior. See [PostgreSQL transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html).

### 6.4 Alternatives and remaining limits

| Alternative | Problem for this requirement |
| --- | --- |
| Check whether the key exists, then insert | Two requests can both observe absence; uniqueness must decide the race anyway. |
| Use merchant reference as the unique key | The current contract deliberately allows repeated references and has no merchant identity. |
| Store keys in an in-memory map | Loses history on restart and cannot coordinate independent instances. |
| Put the claim in Redis and payment in PostgreSQL | Adds another resource whose success/failure must be coordinated; PostgreSQL already supplies the needed durable uniqueness. |
| Store PENDING then complete later | Can support longer external workflows, but needs ownership, timeouts, and stuck-record recovery. |
| Return the current payment on replay | Changes the original operation's result after later transitions. |

The current choice costs storage for keys and snapshots. Keys have global scope and no expiry policy; snapshot compatibility is tied to the v1 response contract. Only creation has this HTTP idempotency protocol. Do not generalize it into an exactly-once promise for all payment actions.

## 7. Double-entry ledger

### 7.1 Why a payment status is insufficient

CAPTURED says which lifecycle step happened. A ledger records the accounting effect with amounts, accounts, sides, and a journal identity. Each current journal has two positive, equal-amount entries in one currency: one debit and one credit. Their totals balance.

For a capture of INR 100.00, this project's model records:

| Operation | Debit | Credit |
| --- | --- | --- |
| CAPTURE | PROCESSOR_CLEARING 100.00 | MERCHANT_PAYABLE 100.00 |
| Full REFUND | MERCHANT_PAYABLE 100.00 | PROCESSOR_CLEARING 100.00 |

PROCESSOR_CLEARING is modeled with a debit normal side; MERCHANT_PAYABLE has a credit normal side. A debit does not universally mean minus. Balance is debits minus credits for the former, credits minus debits for the latter. In this isolated example starting from zero, both modeled balances increase to 100.00 on capture and return to zero after the refund, while all four entries remain in history.

These are the platform model's obligations, not proof that cash moved at a bank. Accounts are pooled per currency because there is no merchant identity. INR and USD totals are never added into a single balance without conversion.

### 7.2 Immutability and reversals

`LedgerTransaction.capture` builds the capture journal. `LedgerTransaction.refund` builds its full reversal and references the original capture. `LedgerService.postCapture/postRefund` must join the payment transaction with MANDATORY propagation.

Deleting or editing the capture to represent a refund would erase the explanation of what happened. Appending a reversal keeps both the original and correction visible. Java records/copied entry lists prevent ordinary object mutation, while SQL UPDATE/DELETE triggers protect persisted accounts, journals, and entries against ordinary mutations.

The immutability claim does not include privileged administrative actions such as TRUNCATE, disabling triggers, or changing schema. Test cleanup uses TRUNCATE deliberately.

### 7.3 Invariants at commit

V3 enforces constraints including at most one capture and one refund per payment, unique reversal use, matching payment/currency/amount/accounts, and complete balanced journal entries. Required journals depend on the final payment state: CAPTURED requires capture without refund, REFUNDED requires both, and other states require neither. Deferred constraint triggers inspect the final database state at commit. That allows a header and its two entries to be inserted as separate SQL statements without accepting a permanently half-written journal.

A normal row CHECK cannot alone establish an aggregate invariant across the complete journal. Java checks explain mistakes early; database checks protect the corresponding stored invariants even when the normal service is bypassed. PostgreSQL CHECK also treats SQL NULL differently from false; V5's envelope predicates use `IS TRUE` so missing JSON metadata cannot pass as an unknown truth value. See [PostgreSQL constraints](https://www.postgresql.org/docs/17/ddl-constraints.html).

### 7.4 Derived balances and migration history

`LedgerRepository.balances` sums immutable entries by account and currency. Deriving balances avoids separately maintaining a mutable total that could disagree with the journal. The cost is aggregation work as history grows; a future materialized total could improve reads but would need its own correctness and reconciliation strategy.

V3 backfilled older CAPTURED/REFUNDED payments with `LEGACY_BACKFILL` journals stamped at migration time. It did not invent historical posting timestamps. New operations use `LIVE`. V4/V5 did not similarly manufacture historical payment events or projections.

**Current limits:** full-amount capture/refund, no partials, fees, merchant wallets, settlement, foreign exchange, or settlement reconciliation. The simple two-leg model intentionally exposes accounting invariants before adding those business cases. M8's projection reconciliation compares application data rather than actual financial settlement.

## 8. Transactional outbox

### 8.1 The database/broker dual-write problem

Imagine committing a capture and then sending an event directly. A crash between those steps leaves a captured payment with no notification. Sending first has the opposite risk: the broker can receive an event for a database transaction that later rolls back.

`OutboxService.record` solves the durable-intent problem by inserting the source event **inside the payment transaction**. Either the payment, its accounting posting, and event commit together, or all roll back. A later worker attempts delivery. The HTTP operation does not wait for Kafka.

`PaymentEvent.from` produces an immutable envelope containing event ID, event type, schema version, payment ID, payment version, occurrence time, and a full `PaymentResponse` snapshot. The payment is flushed first so the event uses its assigned version. Occurrence time is aligned to microseconds for the stored event metadata. A unique `(payment_id, payment_version)` prevents competing envelopes for one source version.

Invalid transitions and creation replays produce no new source event. An event already has its stable UUID before delivery; retrying delivery reuses that event rather than inventing another identity.

Saved event payloads use TEXT containing JSON; database checks and duplicate comparison cast it to JSONB where needed. That separates the stored serialization from logical JSON comparison. JSONB storage/indexing would also be valid if payload querying became a primary requirement; the current choice costs parsing work for those checks.

**Alternatives:** an in-memory application event can be useful when losing work on process exit is acceptable. Direct HTTP notification would still need durable retry and duplicate handling here. Change-data-capture could relay an outbox with less application polling, but introduces a connector/log-position/operations layer. This milestone chose an explicit SQL relay so ownership and failure behavior are visible in the code.

### 8.2 Worker ownership, ordering, and crash recovery

`OutboxDispatcher.dispatchBatch` calls `OutboxDeliveryWorker.dispatchNext`, which runs one event in a REQUIRES_NEW transaction. `OutboxRepository.lockNextDue` uses `FOR UPDATE SKIP LOCKED` so two workers cannot own the same locked row while other workers can find different eligible work.

SKIP LOCKED alone would not preserve payment order: a worker could skip a locked CREATED event and send CAPTURED first. The query also excludes an event when an older version of that payment is not DELIVERED. A backed-off or DEAD predecessor therefore blocks later versions for that payment, while other payments can continue.

The database transaction is the ownership mechanism. A worker crash rolls it back and releases its row lock. There is no PROCESSING state or expiring lease to recover.

The tradeoff is holding a database connection and row lock during a bounded Kafka send. A claim/lease could shorten transaction duration but would require lease expiry, stale-worker fencing, and recovery logic. That is a possible future optimization if measurements justify the complexity.

### 8.3 Failure bookkeeping and bounded retries

When publication fails, the worker throws through its transaction proxy so the delivery transaction rolls back. Only then does `OutboxFailureRecorder.recordFailure` use a fresh transaction to update the attempt count and next eligible time. Continuing to write inside a transaction aborted by a PostgreSQL error would fail.

The failure UPDATE requires PENDING status and the expected previous attempt count. This is a compare-and-set condition: a stale failure cannot overwrite a concurrent success or newer retry decision.

Defaults are batch size 50, one-second scheduler delay, five recorded failure attempts per cycle, one-second initial exponential backoff, and a 60-second backoff cap. Reaching the cycle limit marks the source event DEAD. `attempts` includes acknowledged successes and recorded failures across all cycles; it is not an exact count of physical sends because a crash can occur before bookkeeping. Milestone 6 adds `cycle_attempts` for the current budget and `retry_generation` for recovery preconditions. Both retry delay and exhaustion use the cycle counter; lifetime attempts never reset.

Backoff reduces repeated pressure during an outage. It does not repair permanently invalid data. The local operator `requeue` command now starts a fresh bounded cycle, preserving event identity and lifetime attempts and committing an immutable audit. No HTTP requeue endpoint exists.

### 8.4 LOCAL and Kafka are distinct modes

| Detail | LOCAL, the default | Kafka, opt-in |
| --- | --- | --- |
| Publisher | `DatabasePaymentEventPublisher` | `KafkaPaymentEventPublisher` |
| Destination | `local_payment_event_inbox` | Kafka topic |
| Delivery acknowledgement | Same transaction as local receipt | DELIVERED metadata commits in the current worker transaction after separate broker acknowledgement |
| Consumer projection | Not populated by this path | Populated by the Kafka listener |
| Broker required | No | Yes |

`FINCORE_EVENT_TRANSPORT=kafka` selects Kafka components. `FINCORE_OUTBOX_ENABLED=true` independently enables scheduled source dispatch. Selecting Kafka alone starts consumption but does not schedule publication. Both defaults preserve an easy broker-free local workflow.

Changing transports does not resend already DELIVERED local events. There is no historical event/projection backfill. Create or transition a payment after selecting Kafka to exercise the new route; use the [README's setup instructions](../README.md#kafka-delivery-and-consumer-projection).

## 9. Kafka and consumer processing

### 9.1 Topics, partitions, keys, groups, and offsets

Kafka provides the event-log boundary in this milestone. These terms describe different things:

| Term | Meaning in this project |
| --- | --- |
| Topic | Named stream, default `payment-events.v1`. |
| Partition | Ordered portion of the topic; the development topic has three. |
| Record key | Payment UUID; related events have partition affinity under a stable partitioning setup. |
| Consumer group | Consumers sharing progress and partition assignments; default `fincore-payment-projection-v1`. |
| Offset | Position within one partition, used to track consumed progress. |
| Consumer concurrency | Three listener threads, matching the development partition count. |

Ordering is per partition, not global across all payments. Increasing the partition count can change key placement, so it needs a plan rather than assuming eternal ordering across repartitioning. Kafka retention bounds how much broker history remains available. M9 reconstructs isolated latest-state views from retained local source events; automatic application retention and complete historical broker-replay policy remain future work.

Kafka fits the goal of learning a partitioned, replayable event stream and independent consumer progress. RabbitMQ is a legitimate alternative; it also supports replayable streams, so saying it cannot replay would be wrong. Queue routing, stream semantics, retention, team experience, and operating costs would determine a real selection. This project does not benchmark the brokers. See [Kafka design](https://kafka.apache.org/39/design/design/) and [RabbitMQ streams](https://www.rabbitmq.com/docs/streams).

### 9.2 Acknowledgement and producer idempotence

`KafkaPaymentEventPublisher.publish` sends string-keyed JSON and waits for the send result. `OutboxDeliveryWorker` calls `markDelivered` only after that succeeds. Returning immediately with an unresolved future would let the source claim success too early.

`KafkaEventConfiguration` sets `acks=all`, producer idempotence, and one in-flight request. Request and metadata-block limits are five seconds, delivery timeout ten seconds, and the default publisher future wait fifteen seconds. These bound configured waits rather than allowing an unbounded send inside the database transaction.

`acks=all` concerns in-sync broker replicas. Replication factor one still means a single copy: this learning cluster has no broker-failure redundancy. Producer idempotence suppresses duplicates from the producer's retry protocol; it cannot suppress every fresh application send after a database rollback. See [Kafka producer configuration](https://kafka.apache.org/39/configuration/producer-configs/).

**The key failure window:** Kafka accepts event E; the source transaction then fails before committing DELIVERED. E remains eligible in the outbox and may be sent again. The consumer must tolerate that repetition.

### 9.3 Durable consumer receipts and conflicts

`KafkaPaymentEventListener.onMessage` parses and validates the envelope and requires the record key to equal its payment UUID. Required fields, schema version 1, integer versions, named enums, amount precision, and a single JSON document are checked. String codecs and explicit JSON avoid depending on Java class-name type headers. Avro/Protobuf plus a schema registry could strengthen compatibility tooling, at the cost of another contract/tooling workflow; those are not implemented.

`PaymentEventConsumer.consume` performs two related writes in one READ COMMITTED transaction:

1. Claim a receipt in `consumed_payment_events` using database uniqueness and `ON CONFLICT DO NOTHING`.
2. For a new accepted receipt, insert or conditionally update `payment_event_projections`.

| Incoming record | Result |
| --- | --- |
| New event ID and payment version | Receipt saved; newer projection may be applied. |
| Same event ID and same logical envelope | Baseline deduplicates; after enrollment the shadow is still visited and both latest views must agree. |
| Same event ID but changed content | Conflict; no silent overwrite. |
| Different event ID claiming the same payment version | Conflict. |
| New unique event with an older payment version | Receipt saved; latest projection remains unchanged. |

Duplicate comparison uses logical JSONB equality, so object field order and insignificant numeric scale do not manufacture a conflict. PostgreSQL uniqueness coordinates simultaneous consumers; a JVM set would neither survive restart nor coordinate independent processes.

The logical processing identity is `PaymentEventConsumer.CONSUMER_NAME = "payment-projection-v1"`. It is separate from the configurable Kafka group. Starting a new group changes broker progress, but does not erase existing receipts or automatically rebuild this projection.

### 9.4 Database commit before offset commit

The listener calls the proxied `ConsumerRecordProcessor.process` transaction. It locks the durable source position, checks explicit quarantine, and otherwise commits receipt/projection and position/failure resolution together. The REQUIRED `PaymentEventConsumer.consume` joins this transaction. Only after commit does the listener return. Auto-commit is disabled and `AckMode.RECORD` lets the container commit the offset after that successful return. A durable quarantine permits return while retaining an explicit unprocessed gap. See [Spring Kafka acknowledgement modes](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html).

If projection writing fails, the receipt also rolls back and the offset is not advanced. If the database commits but the process stops before its offset commit, redelivery finds the existing receipt. Committing the offset first would risk permanently skipping unapplied work.

The guarantee is **at-least-once transport with idempotent consumer database effects**. It is not one distributed exactly-once transaction. Kafka transactions alone would not automatically make these PostgreSQL writes atomic with Kafka. External email, HTTP calls, or other side effects would require their own durable design; they cannot be added casually inside this receipt transaction and inherit its guarantee.

### 9.5 Projection versions and eventual consistency

The SQL upsert updates only when incoming `paymentVersion` is greater than the stored version. For example, receiving CAPTURED version 2 and then AUTHORIZED version 1 must leave version 2 visible. Blind last-arrival-wins updates would regress the view during replay or delayed delivery.

The events carry full snapshots rather than only deltas. This makes the newest snapshot useful even if earlier versions are absent, at the cost of larger messages and repeated fields. A delta-based consumer would need complete ordered inputs or gap recovery to construct the same state.

`GET /api/v1/payments/{paymentId}/projection` may return an older snapshot or `PAYMENT_PROJECTION_NOT_FOUND` after the payment request has succeeded. That is **eventual consistency**, not proof the payment is missing. Read the ordinary payment endpoint when you need authoritative source state.

This is a CQRS-like separation of write model and read model. It is not event sourcing: `payments` remains authoritative, events only began with V4 and historical coverage is incomplete. M9 can rebuild an isolated latest-state projection from retained current source snapshots; it does not reconstruct missing historical facts or accounting effects. The consumer never calls `PaymentService` to repeat capture/refund or writes ledger entries.

### 9.6 Poison records, explicit quarantine and known gaps

Consumer failures use unlimited retries with a one-second backoff by default. A malformed, unsupported, or conflicting event blocks later work on its partition until repaired or explicitly quarantined. Milestone 7's local command accepts one observed permanent failure with an immutable decision, expected fingerprint and explicit projection-gap acknowledgement. No automatic skip was introduced.

Milestone 6 persists failure evidence in an independent transaction after failed receipt/projection work has rolled back. Operator views show topic, partition, offset, safe error category, attempts, and timestamps; original key/value stay private in the database. Failure-recording errors preserve the original processing failure. Failed resolution prevents offset acknowledgement, allowing receipt-based safe replay.

Failures and successes lock the same durable source-position row. Its length-prefixed, nullable-field-safe SHA-256 fingerprint identifies the exact key/value; successful processing is irreversible for that identity. This prevents a late failure from an old partition owner reopening a record that a new owner already processed. Every successfully consumed record also leaves a compact position marker, so success before the first observed failure is covered. This costs storage per physical record and needs a future retention policy. `FINCORE_KAFKA_SOURCE_NAME` identifies the cluster/topic generation; rotate it when that generation changes so reused offsets cannot overwrite old evidence.

`ConsumerRecoveryService.quarantine` and live processing lock the same position to prevent conflicting decisions. Quarantine retains original decoded evidence and leaves processed/resolved markers null. `gaps` remains DEGRADED. Later `replay` validates only that preserved original and commits its idempotent projection work, successful markers and immutable audit together. A still-invalid original remains a gap. `NO_KNOWN_GAPS` describes tracked incidents in the configured scope and never certifies historical completeness. This policy is specific to the read projection, not every future financial consumer. See [the M7 learning guide](MILESTONE_7_LEARNING_GUIDE.md) and [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

## 10. Identities and versions

Many difficult bugs come from using one identity in place of another.

| Identity | Answers | Does not replace |
| --- | --- | --- |
| Idempotency key | Is this a retry of a creation operation? | Payment ID, authentication, or lifecycle locking |
| Request fingerprint | Does this key still describe the same logical input? | Encryption or request ownership |
| Payment UUID | Which payment is involved? | A retry key: different operations concern the same payment |
| JPA/payment version | Which revision of this payment is this? | Event schema version or a timestamp |
| Event UUID | Is this the same logical event being delivered again? | Payment UUID: one payment emits multiple events |
| Event schema version | Which envelope contract should be interpreted? | Payment lifecycle progress |
| Ledger transaction UUID | Which accounting journal/reversal is this? | Kafka delivery identity |
| Kafka group | Which subscription shares broker progress? | Durable application receipt identity |
| Logical consumer name | Which application's processing receipts apply? | Offset tracking |
| Topic + partition + offset | Where is this physical broker record? | Event identity: E can appear at several offsets |
| Rebuild run / generation | Which isolated target and frozen input plan were approved? | Current source freshness or reader ownership |
| Routing request UUID | Is this an exact retry of an accepted authenticated route decision? | Event identity, actor authentication or a new switch |
| Route revision | Does new operator intent match the current route? | Frozen build generation or historical event completeness |

Request idempotency prevents duplicate accepted creations. Optimistic locking resolves conflicting writes. Consumer idempotency prevents repeated processing effects. None is a substitute for the other two.

## 11. A complete payment story and its failure cases

### 11.1 One INR 100.00 payment

Assume Kafka is selected and scheduled dispatch is enabled. Version numbers below describe the normal new-payment flow.

1. The client POSTs creation with key `order-123-create` and amount `100.00`. `PaymentController.createPayment` calls `PaymentService.createPayment`. The claim, payment, and CREATED event commit together. Payment version is 0. There are no ledger entries yet.
2. The client loses the response and repeats the same key/input. It receives the original creation snapshot with 200. No second payment or event is created.
3. Authorization calls `Payment.authorize` in `PaymentService.authorizePayment`. The payment becomes AUTHORIZED, version 1, and gains its event. No ledger journal is posted.
4. Capture calls `PaymentService.capturePayment`: mutate, version-checked flush, `LedgerService.postCapture`, `OutboxService.record`, commit. Version 2 is CAPTURED; the two INR 100.00 ledger entries and CAPTURED event exist atomically.
5. Dispatch sends versions 0, 1, and 2 in payment order. For each broker acknowledgement, the source worker can commit DELIVERED. Consumer timing is independent: it may project a record before that source metadata commit finishes.
6. The consumer saves receipts and advances the snapshot to CAPTURED version 2. A repeated delivery of any identical event adds no second effect. The projection read eventually shows version 2.
7. A full refund adds version 3, a new REFUNDED event, and a reversal journal referencing the capture. The accounting balances net to zero; the history is retained. The consumer eventually displays REFUNDED.
8. Replaying the original creation key still returns the saved CREATED response. Reading the payment returns REFUNDED. Reading its projection returns the latest consumed version, possibly briefly behind REFUNDED.

The exact capture path in the code is:

```text
POST /api/v1/payments/{paymentId}/capture
→ PaymentController.capturePayment
→ PaymentService.capturePayment
→ Payment.capture → PaymentRepository.flush
→ LedgerService.postCapture → LedgerRepository.save
→ OutboxService.record → OutboxRepository.save
→ SOURCE DATABASE COMMIT

OutboxDispatcher.dispatchBatch → OutboxDeliveryWorker.dispatchNext
→ OutboxRepository.lockNextDue → KafkaPaymentEventPublisher.publish → Kafka
→ acknowledged send → OutboxRepository.markDelivered → SOURCE METADATA COMMIT

KafkaPaymentEventListener.onMessage → ConsumerRecordProcessor.process
→ PaymentEventConsumer.consume
→ PaymentEventConsumerRepository.insertReceipt + applyProjection
→ ConsumerFailureRepository.resolve
→ CONSUMER DATABASE COMMIT → Kafka offset commit
```

The last two branches proceed independently after publication; the diagram does not impose a consumer-after-DELIVERED ordering.

### 11.2 What each failure leaves behind

| Failure | Durable outcome / recovery |
| --- | --- |
| Response lost after successful creation | Same creation key returns its saved result. |
| Same key reused with changed amount | 409; original creation remains intact. |
| Two stale concurrent capture requests | One wins; the other conflicts; no second capture journal commits. |
| Ledger/outbox write fails after payment flush | Source transaction rolls back payment version/state and related inserts. |
| Process stops before source commit | No committed payment change/event from that transaction. |
| Broker unavailable after source commit | Payment/ledger stay committed; outbox retains delivery intent and applies retry policy. |
| Kafka accepts event but outbox commit fails | Event can be resent under the same event UUID; consumer receipt deduplicates it. |
| Consumer projection write fails | Its receipt and projection roll back; offset is retained for retry. |
| Consumer DB commits, offset commit is lost | Record may repeat; its durable receipt makes repetition harmless. |
| Older unique snapshot arrives late | Receipt is retained; newer projection is preserved. |
| Source event reaches DEAD | Later versions of that payment are blocked; an audited local requeue can start another cycle after the cause is repaired. |
| Poison consumer record | Blocks by default; explicit audited quarantine permits progress while retaining an unresolved gap. |
| Diagnostic success write fails inside processing | Receipt/projection and position resolution roll back together; offset stays unacknowledged. |
| Original quarantined record becomes compatible after a fix | Audited original replay applies/receipts it without regressing a newer projection and atomically closes that gap. |

Every row is about a particular boundary. A passing happy-path POST alone would establish very little about these cases.

## 12. Why the recent developments matter

### 12.1 The platform can communicate committed facts

Before the outbox, a correct payment row and journal would still leave notification reliability unanswered. V4 connects financial acceptance to durable delivery intent. V5 connects that intent to a real broker and a consumer that tolerates repeated delivery.

That changes the platform from a synchronous record-keeping API into a foundation for asynchronous downstream processing. A future reporting view, reconciliation workflow, fraud consumer, or webhook dispatcher could subscribe to facts without making the original capture request wait for every downstream dependency. Those consumers are possibilities, not implemented features.

### 12.2 The new complexity has a purpose and a cost

| Gain | What now requires attention |
| --- | --- |
| Broker failure need not undo an already accepted payment | Outbox backlog, retry limits, and blocked events |
| Consumer work can progress independently | Lag, unavailable projections, and read consistency |
| Retried transport can be safe | Stable event IDs and durable receipt retention |
| Multiple read views can be added | Contract evolution, consumer ownership, and rebuild plans |
| Financial work stays in one trusted transaction | Downstream effects must not replay financial commands |
| A rebuilt view can safely become the served view | Freshness fencing, authenticated decisions, target equivalence and continued live maintenance |
| Rollback retains later consumed facts | Two warm targets, bounded fanout cost and explicit replacement/retirement policy |

These additions matter because real distributed systems expose uncertainty between commits. More components alone are not an improvement. Their value here is the explicit boundary, durable state, and tested recovery behavior.

### 12.3 From correct facts to controlled recovery

Milestones 1–5 answer whether a request, journal and asynchronous event can survive retries. Milestones 6–10 answer how an operator can understand a failure and change a read model without creating another financial operation. Each adds a different kind of evidence or authority:

```text
6: locate a blocked boundary and audit a source requeue
→ 7: record a deliberate quarantine or replay the original consumer evidence
→ 8: compare source, receipts and projection; keep coverage/gaps explicit
→ 9: build a separate latest-state view from frozen retained inputs
→ 10: authenticate a decision, recheck under a fence, switch reads and maintain both views
```

Consider a captured payment whose earliest outbox event is DEAD. Later versions wait behind it, even though capture and its journal already committed. A known-zero Kafka lag can coexist with this source backlog because Kafka cannot count an unpublished record. Milestone 6 identifies the blocker; its audited requeue opens another delivery cycle without recapturing the payment.

Now suppose a valid retained consumer record is rejected because of a decoder compatibility bug. Explicit quarantine can let later records proceed while keeping an unresolved gap. A later full snapshot may make the projection's current fields agree with the payment. Milestone 8 can report that agreement alongside the gap; those observations answer different questions. A truly invalid original remains unresolved by original-only replay.

Milestone 9 can build another latest-state view while that incident remains open. It preserves the input and the observed coverage rather than inventing the missing history. Milestone 10 still refuses promotion while the tracked gap is open. If a compatible decoder later accepts the original and audited replay resolves the incident, a new routing request can recheck all current conditions. A prior successful validation never becomes a permanent permission slip.

After a successful cutover, a later refund reaches both maintained projections when the valid event is consumed. Rolling reads back to the baseline keeps that newer refund snapshot; it does not restore the earlier build version or reverse the refund journal. This is why diagnostic state, historical build evidence, financial state and active routing need separate identities and lifetimes.

### 12.4 What this still does not establish

We have not demonstrated high throughput, multi-broker failover, real settlement, a complete production security model, or automatic incident recovery. Single-node Kafka has no redundant broker copy. Milestone 6 adds visibility and controlled source recovery; Milestone 7 adds explicit quarantine/original replay for this read consumer. Milestone 8 adds reconciliation and retained coverage assessment; Milestone 9 builds isolated shadow views with frozen generations and catch-up. Milestone 10 closes the observation-to-routing gap through fresh fenced promotion/rollback and keeps baseline plus one shadow warm. Its authenticated operator surface does not secure the public learning payment API. Target replacement/retirement and arbitrary correction remain future work.

## 13. Testing and current readiness

### 13.1 What the testing tools prove

| Tool / technique | Used for | Limit |
| --- | --- | --- |
| JUnit 5 / parameterized tests | State/action combinations, validation, normalization, configuration | Java-level checks alone do not prove SQL behavior. |
| Mockito | Isolated collaborators and injected failure windows | A mock repository cannot prove rollback or database uniqueness. |
| MockMvc | Routes, request binding, validation, JSON, status/error responses | It does not establish real broker delivery by itself. |
| Real PostgreSQL suites | Constraints, migrations, shared transactions, races, rollback | Results depend on the actual database/version tested. |
| Testcontainers | Repeatable disposable database/broker environments when Docker exists | Skipped tests are not passing tests. |
| Real Kafka/PostgreSQL suites | Acknowledgement, listener commits, duplicates, offset retention | One local broker cannot prove cluster failover. |
| Barriers, latches, Awaitility, offset checks | Coordinate races and await observable completion | Arbitrary sleeps would be slower and less deterministic. |
| Maven Surefire / Failsafe | Unit `test` phase / integration `verify` phase | Report scopes and skips must be inspected. |

Docker Compose is for local services; the executable application runs separately. KRaft is Kafka's broker/controller metadata mode in the single-node development setup; no ZooKeeper service is required by this configuration. Environment variables select ports, credentials, transport, and scheduling. Validated configuration properties catch invalid settings early instead of hiding a typo until business work arrives.

### 13.2 Verification history

The Milestone 5 implementation recorded this baseline:

- **168** unit/service/configuration/MVC checks passed.
- **72** real local PostgreSQL checks passed: 17 idempotency, 25 ledger, 16 outbox, and 14 consumer-projection invocations.
- **4** real Kafka/PostgreSQL checks passed: lifecycle delivery, identical redelivery, broker success followed by outbox rollback, and consumer rollback retaining its offset before retry.
- Java 21 compiled and packaged the application; V1–V5 migrations and Hibernate validation succeeded, including the legacy-upgrade regression.
- **78 Docker-dependent methods were skipped**, because Docker was unavailable. Compose and the PostgreSQL 17.6 container workflow remain unverified. Local PostgreSQL was 18.6, newer than the Flyway version's tested support; native Kafka was 3.9.1.

One initial broker test assertion expected an unwrapped injected exception; Spring translated it at the repository boundary. The corrected assertion passed on rerun. Earlier session counts are historical snapshots, not additional independent tests to add to the current count.

Do not add historical session counts together as new independent evidence. Current Milestone 10 build, database, broker, HTTP security and packaged-CLI results are recorded separately in [MILESTONE_10_SUMMARY.md](MILESTONE_10_SUMMARY.md) and [SESSION_LOG.md](SESSION_LOG.md). Earlier summaries retain their historical verification counts.

### 13.3 Current evidence through Milestone 10

The recorded Milestone 10 evidence contains **534 distinct executed checks passed**:

| Check boundary | Passed checks | What the result covers |
| --- | ---: | --- |
| Unit, service, configuration, MVC and CLI parsing | 307 | Java behavior, strict request counters, credentials, authorization and CSRF |
| Real PostgreSQL | 215 | Includes 39 routing cases, durable guards, rollback, races and stale snapshots |
| Real Kafka with PostgreSQL | 7 | Acknowledgements, offsets, retries, quarantine/replay and maintained-view cutover |
| Packaged operations CLI | 5 | Headless execution, recovery and rebuild commands with an unreachable broker |

This is combined full-suite evidence plus the final successful 39-case routing rerun after a test-only exception-wrapper assertion correction. It is not a claim that the last single full `verify` invocation succeeded. The [Milestone 10 summary](MILESTONE_10_SUMMARY.md) records the sequence and logs. No new application test run was performed merely to expand this document.

**224 Docker-dependent methods were skipped.** Actual local services were PostgreSQL 18.6 and Kafka 3.9.1; PostgreSQL 17.6 containers, multi-broker failover and production-volume/load behavior remain unverified. Fresh V1–V10 migration/Hibernate validation and the legacy upgrade passed; earlier migration files were preserved. A readable guide explains the evidence; it does not enlarge its scope.

### 13.4 Connection pools are a shared resource budget

A connection pool retains reusable database connections. Spring caches test application contexts, and different contexts can retain different pools at the same time. Eleven contexts with the default ten-connection pools exhausted the disposable PostgreSQL server's 100-client limit before packaged CLI processes could connect. A passing test in isolation therefore did not establish that the complete suite's resource budget was sound.

[Test-only application.properties](../src/test/resources/application.properties) limits each pool to five connections with `minimum-idle=0`. This bounds test resource use without raising production database limits or changing the normal application's defaults. Increasing server limits would be another option if capacity justified it; leaking more test connections would still need attention. Nested `REQUIRES_NEW` calls also need room for an independent connection while their caller's transaction remains suspended.

## 14. Milestone 6: event operations and audited outbox recovery

### 14.1 Operational state answers a different question

A successful capture means the payment and accounting transaction committed. **Operational state** describes whether its event was delivered and consumed. Confusing these boundaries can lead an operator to repeat a financial operation when only delivery needs recovery.

Milestone 6 introduced [EventOperationsCli](../src/main/java/com/fincore/payments/operations/EventOperationsCli.java). Commands `overview`, `events`, `lag`, `failures`, `requeue` and `requeues` expose different evidence. The packaged CLI starts Spring with HTTP, dispatch scheduling, listeners and automatic topic creation disabled. Inspection therefore starts no relay or consumer. Pending Flyway migrations still run.

The CLI uses configured database access. Its `--operator` value is self-declared audit text. The authenticated routing endpoints added in Milestone 10 do not turn these earlier CLI commands into authenticated network operations.

### 14.2 One pending count contains different causes

[EventOperationsRepository.overview](../src/main/java/com/fincore/payments/repository/EventOperationsRepository.java) calculates its counts in one SQL statement, so the buckets share one PostgreSQL statement snapshot:

| Pending bucket | Meaning |
| --- | --- |
| Ready | Its retry time arrived and no earlier undelivered version of this payment blocks it. |
| Backed off | No ordering blocker exists, but its retry time is in the future. |
| Blocked | An earlier version for the same payment is not DELIVERED. |

These buckets partition PENDING rows. The `events` query identifies the oldest blocker. A DEAD version 0 can hold up version 1; resetting version 1 would miss the cause.

A ready row can currently be locked by a dispatcher: eligibility does not measure worker capacity. Separate count queries could observe different moments and disagree. Retained-history query cost and historical monitoring remain separate concerns.

### 14.3 Retry attempts, cycles and generations

Automatic retry is bounded. Explicit recovery grants another cycle without rewriting the original event or erasing previous work. Three counters explain that distinction:

```text
Five failed attempts: attempts=5, cycleAttempts=5, retryGeneration=0, DEAD
Operator requeue:     attempts=5, cycleAttempts=0, retryGeneration=1, PENDING
Successful delivery: attempts=6, cycleAttempts=1, retryGeneration=1, DELIVERED
```

`attempts` counts recorded lifetime failures and successful delivery; a crash before bookkeeping means it is not an exact count of physical network sends. `cycleAttempts` counts the current bounded automatic cycle. `retryGeneration` advances for an accepted explicit requeue. A generation is an operator concurrency token; it is neither the payment version nor the event schema version.

[EventOperationsService.requeue](../src/main/java/com/fincore/payments/service/EventOperationsService.java) accepts a DEAD event and the expected generation. It resets the cycle and delivery metadata while retaining identity, payload and lifetime attempts. Source payment state and ledger postings are untouched.

### 14.4 Locking, compare-and-set and exact command replay

These three protections have separate purposes. A `FOR UPDATE` row lock serializes operators modifying one event. The DEAD/expected-generation condition is **compare-and-set**: change the state only if it is still the state reviewed. The request UUID identifies the accepted command when its response is lost.

After acquiring the lock, the service checks `outbox_requeue_audit` for that request. At READ COMMITTED, a fresh statement can see an audit committed while this caller waited. An identical retry returns the original resulting generation and timestamp even if delivery has since progressed. Changed event, generation, operator or reason under the same request UUID conflicts. A globally unique request constraint also arbitrates concurrent reuse across different event locks.

The reset and immutable audit commit together. V6 database guards reject decreasing counters, unaudited resets and invalid generation changes; deferred checks allow the required audit relationship to be established before commit.

```text
requeue → EventOperationsService.requeue
→ EventOperationsRepository.lockEvent → exact request lookup
→ DEAD + expected-generation check → requeue + insertAudit → COMMIT
→ ordinary OutboxDeliveryWorker later attempts the same event
```

### 14.5 Lag and durable failure evidence

[KafkaLagService.report](../src/main/java/com/fincore/payments/service/KafkaLagService.java) reads broker end offsets and configured group commits through Kafka Admin under a bounded report deadline. A known partition lag is end offset minus committed offset. The total is known only when all required partition values are known. DISABLED, UNINITIALIZED, INCONSISTENT and UNAVAILABLE are not measured zero. DELIVERED source rows cannot substitute for group offsets.

[ConsumerFailureService.failed](../src/main/java/com/fincore/payments/service/ConsumerFailureService.java) persists failed-record evidence in REQUIRES_NEW after processing rolls back. Evidence retains original StringDeserializer-decoded key/value text; CLI output exposes positions and stable codes rather than that payload. `consumer_event_failures` tracks incidents, while `consumer_event_positions` holds a compact fingerprint and irreversible success marker even for initially healthy records.

Processing and failure reporting lock the same physical position. If an old partition owner reports failure after a new owner succeeded, the marker prevents a false OPEN incident. Source namespace, logical consumer, topic, partition and offset scope the position. Namespace rotation after replacing topic history avoids reusing an old position identity; it does not reset logical event deduplication.

### 14.6 Failure example, alternatives and costs

Suppose Kafka becomes unreachable, exhausting version 0's cycle while version 1 waits. Repair connectivity, inspect the blocker, then requeue its observed generation. Dispatch retries the original envelope. If the response disappears, repeat the exact command. Inspect delivery, projection and lag separately.

| Choice | Alternative | Why it fits here and its cost |
| --- | --- | --- |
| Lifetime counter plus bounded cycles | Reset all attempts or retry forever | Preserves incident history and explicit recovery ownership; adds state and commands. |
| Database lock, expected generation and request audit | JVM lock or blind SQL reset | Coordinates processes and stale intent; adds transaction contention and PostgreSQL guards. |
| Durable position success marker | Logs alone or mutable failure status | Handles restart and stale-owner races; stores a marker per physical record. |
| Explicit unknown lag states | Treat missing commits/broker errors as zero | Prevents unsupported reassurance; operators must interpret uncertainty. |

These choices do not add alerting or retention. Source requeue also cannot repair an invalid record already blocking a consumer offset. The [operations runbook](EVENT_OPERATIONS_RUNBOOK.md) gives commands; the [Milestone 6 guide](MILESTONE_6_LEARNING_GUIDE.md) preserves the deeper incident model.

## 15. Milestone 7: consumer quarantine, original replay, and known gaps

### 15.1 Progress and completeness are separate properties

A **poison record** repeatedly fails processing, blocking healthy later records on its partition. Milestone 7 introduced a choice for this latest-payment projection: continue blocking, or accept a documented missing-input obligation so processing can continue.

**Quarantine** records a durable disposition for one physical record without claiming the event was applied. The operator explicitly accepts a projection gap. Only an observed open `INVALID_EVENT` or `EVENT_CONFLICT` is eligible; `CONSUMER_FAILED`, such as a database outage, cannot justify skipping work.

Quarantine never calls capture/refund or posts accounting. A settlement consumer requiring every event's effect would need its own recovery policy.

### 15.2 Evidence, identity and the state model

Three identities remain distinct: the event UUID identifies a logical event, source namespace/topic/partition/offset identifies its physical delivery, and request UUID identifies an accepted operator decision. The expected SHA-256 fingerprint confirms which preserved decoded key/value the operator reviewed. A duplicate event at another offset has another physical position and cannot inherit this position's quarantine.

```text
processing failure → OPEN failure + preserved original evidence
→ explicit QUARANTINE audit → listener may acknowledge → gap remains
→ compatible fix → original replay
→ receipt + projection + success/resolution + REPLAY audit COMMIT → gap closes
```

[ConsumerRecoveryService.quarantine](../src/main/java/com/fincore/payments/service/ConsumerRecoveryService.java) verifies configured source/topic and the fixed `payment-projection-v1` identity. It locks the common position, re-reads evidence and accepted request state, then checks fingerprint, open state and eligibility. It inserts an immutable `consumer_recovery_audit` decision without manufacturing successful processing. Exact request/action/arguments replay the original accepted result; changed intent under the same UUID conflicts.

The CLI moves no Kafka offsets. On redelivery, the updated listener observes quarantine and can return successfully. Old builds that ignore quarantine are incompatible with this workflow.

### 15.3 Atomic processing before acknowledgement

[ConsumerRecordProcessor.process](../src/main/java/com/fincore/payments/service/ConsumerRecordProcessor.java) establishes the transaction that arbitrates live work against recovery:

```text
KafkaPaymentEventListener → ConsumerRecordProcessor.process
→ ConsumerFailureRepository.lockPosition → quarantine check
→ PaymentEventRecordDecoder.decode → PaymentEventConsumer.consume
→ ConsumerFailureRepository.resolve → database COMMIT → Kafka acknowledgement
```

For a quarantined position, processing returns after the disposition check without setting success. Otherwise receipt, version-guarded projection and position/failure resolution share the transaction. A resolution failure rolls back the receipt and projection too. If processing fails, diagnostics persist separately through `ConsumerFailureService.failed`; the original failure is still thrown.

`PaymentEventConsumer.consume` uses REQUIRED propagation, joining the processor or recovery transaction. The current Milestone 10 implementation additionally checks the actual JDBC connection isolation is READ COMMITTED before taking its shared routing fence. An annotation on an inner REQUIRED method cannot change an inherited transaction's isolation, so an incompatible inherited snapshot is rejected explicitly.

After enrollment, the same transaction maintains baseline and the one warm shadow, including during recovery. Diagnostics and quarantine remain scoped to the baseline logical identity; the reader's active route does not create another failure namespace.

### 15.4 Replay means the preserved original

[ConsumerRecoveryService.replay](../src/main/java/com/fincore/payments/service/ConsumerRecoveryService.java) reads preserved evidence; no replacement JSON is accepted. `PaymentEventRecordDecoder.decode` applies the live wire contract, including envelope and payment-key checks. Replay commits consumer effects, position/failure resolution and immutable REPLAY audit together. V8 deferred constraints enforce that relationship.

A compatible decoder fix can make an originally rejected valid representation processable. Malformed JSON remains malformed. If decoding, identity checks, fanout agreement, resolution or audit fails, everything rolls back and the gap stays open. There is no force-close command.

An older replay can record a previously missing receipt while the version-guarded projection keeps a newer snapshot. This closes that original record's processing obligation without moving the latest state backwards. It creates no replacement broker record and repeats no financial operation.

Quarantine remains recorded after replay. Redelivery of that position can return using its durable disposition.

### 15.5 Known gaps are scoped observations

`ConsumerRecoveryService.gaps` reports DEGRADED for unresolved quarantines or other open failures in the configured source/consumer/topic. NO_KNOWN_GAPS means no tracked unresolved incident in that scope. It cannot establish that all historical events existed, were retained, or were processed before diagnostic tracking began. Rotating namespaces changes the observation scope and does not erase older obligations.

Zero Kafka lag can coexist with a quarantined gap: offsets measure progress, while the gap tracks unapplied input. A newer full snapshot may make current visible payment fields correct without proving earlier input coverage. This distinction motivates the reconciliation and rebuild milestones that follow.

Recovery evidence is decoded text, not a raw byte/header archive. There is no retention policy or authenticated network API for these CLI recovery actions. Their operator label remains self-declared, despite Milestone 10 authenticating its separate routing endpoints.

### 15.6 Failure example, alternatives and costs

Suppose an older decoder rejects a valid event. Inspect its OPEN failure and fingerprint, then explicitly quarantine it. Healthy records resume, but `gaps` stays DEGRADED. After a compatible decoder fix, replay the preserved original. Success closes the gap; lost output is recovered by repeating the exact request UUID and arguments.

| Choice | Alternative | Why it fits here and its cost |
| --- | --- | --- |
| Explicit database quarantine | Automatic skip or dead-letter publication | Uses existing durable evidence and reviewed gap acceptance; needs operator action and leaves a completeness obligation. |
| Common position lock | Separate unlocked status checks | Arbitrates processing/recovery races across processes; adds contention and lock-order discipline. |
| Original-only replay | Arbitrary replacement payload | Preserves logical identity and reviewable coverage; genuinely invalid originals need a separate correction design. |
| Receipt, resolution and audit in one transaction | Commit projection then log success separately | Prevents partial accepted recovery; couples recovery availability to all database checks. |

A dead-letter topic needs its own transfer, retention and replay guarantees. See the [consumer recovery runbook](CONSUMER_RECOVERY_RUNBOOK.md) and [Milestone 7 guide](MILESTONE_7_LEARNING_GUIDE.md) for commands and further crash cases.

## 16. Milestone 8: projection reconciliation and source coverage

### 16.1 Why processing progress cannot prove data agreement

Milestone 8 asks whether the stored read model agrees with the facts the application retained. Earlier protections answer narrower questions. DELIVERED means the selected transport accepted an outbox event; LOCAL acceptance does not create a Kafka projection. Zero broker lag can include a deliberately quarantined record. A projection at version 2 can coexist with missing receipts for versions 0 and 1. Each signal is useful, but none alone proves complete processing.

**Reconciliation** means comparing independent records and reporting where their claims differ. Here it compares authoritative `payments`, retained `payment_outbox` events, durable `consumed_payment_events` receipts and replaceable `payment_event_projections`. Physical-position failure and recovery records add incident context. This is payment-projection reconciliation inside the same PostgreSQL database; it does not establish bank settlement or actual movement of funds.

The implementation deliberately observes before attempting repair. A disagreement can indicate ordinary asynchronous delay, missing source history, an identity conflict or damaged projection state. Those cases need different responses. Overwriting every mismatch would hide the evidence needed to choose safely.

### 16.2 A stable observation with an explicit lifetime

[`ProjectionReconciliationService.reconcile`](../src/main/java/com/fincore/payments/service/ProjectionReconciliationService.java) owns a read-only `REQUIRES_NEW` transaction at Repeatable Read, with a 30-second timeout. Repeatable Read gives the report's count, comparison page and gap queries one committed database snapshot. A capture committing between those queries cannot make different parts describe incompatible payment versions.

`REQUIRES_NEW` matters when another transaction already exists: the report suspends it and establishes its own isolation rather than joining a caller's weaker or older transaction. That independence consumes another connection while nested. It fits this bounded diagnostic operation; it would be a poor blanket rule for business operations whose effects must commit together.

Snapshot consistency lasts for one call. A later page gets a fresh snapshot. Consequently, several CLI invocations are an interactive investigation, not one frozen export or a promise that all concurrent inserts were visited.

### 16.3 Finding everything that needs comparison

[`ProjectionReconciliationRepository`](../src/main/java/com/fincore/payments/repository/ProjectionReconciliationRepository.java) takes the union of payment IDs from source payments, logical consumer receipts and projections. Starting only from source rows would hide consumer-only records; starting with an inner join would also hide the missing side of a disagreement.

The command supports a single-payment filter or UUID keyset pagination, with a limit from 1 to 100. The repository reads one extra row to determine `hasMore`; the report returns `nextAfterPaymentId` when appropriate. A page saying `NO_OBSERVATIONS_ON_PAGE` describes that page alone. Other pages and global incident counts can still contain problems.

Keyset pagination avoids repeatedly counting past earlier result rows, but UUID order is not commit order. An inserted UUID can sort before an already used cursor. Also, bounded output does not imply constant query cost: total counts and comparison aggregates still inspect database data.

### 16.4 Identity, content and coverage are separate claims

A receipt must agree on event UUID, payment UUID, payment version and logical JSON content. Version alone cannot distinguish two conflicting facts claiming the same position in a payment's history. JSONB equality accepts harmless object-field ordering and insignificant numeric scale differences while detecting changed interpreted content. The original stored text remains evidence; comparison does not rewrite it.

Three kinds of coverage remain distinct:

- **Retained history:** do stored source versions run from zero through the current version without holes?
- **Logical receipts:** does each retained event have the matching consumer identity and content?
- **Latest projection:** do its event, version and business fields agree with the source snapshot and its own receipt?

Even contiguous retained versions cannot certify original historical completeness. V4 did not invent events for older payments, and the report cannot establish facts that were never retained. `historicalCompleteness` therefore remains `NOT_CERTIFIED`.

Logical receipts span source namespaces under `payment-projection-v1`. Changing the configured Kafka source or topic does not erase them. The report includes configured-scope and other-scope recovery gaps. Its output contains IDs, counts, versions and findings rather than exporting merchant references or raw payloads.

### 16.5 A concrete missing-view failure

Suppose event E was consumed successfully, its immutable receipt remains, and its replaceable projection is later missing. Ordinary redelivery finds the receipt and deduplicates. It cannot safely pretend E is a new event just to recreate the view.

Reconciliation reports `PROJECTION_MISSING` and `LATEST_RECEIPT_NOT_PROJECTED`. It preserves the receipt and may identify the current strict-decoded source snapshot as a candidate for a separate rebuild. Candidate status is evidence for planning, not authorization to delete receipts, resolve quarantine or mutate live data. This is the practical reason Milestone 9 introduces another processing identity.

### 16.6 Choices and costs

| Choice | Alternative | Why it fits here, and its cost |
| --- | --- | --- |
| Read-only comparison first | Automatically overwrite mismatches | Preserves disputed facts before a repair policy exists; operators must review findings. |
| Repeatable Read snapshot | Independent Read Committed queries | Keeps one report internally consistent; separate pages still observe different snapshots. |
| Union of source and consumer IDs | Inner join from payments | Exposes one-sided artifacts; SQL and real-database verification become more involved. |
| Identity plus JSONB content | Compare only versions or raw text | Detects conflicting meaning while tolerating formatting; depends on explicit envelope semantics. |
| Bounded keyset page | Unbounded export or OFFSET | Controls returned rows and avoids walking prior pages; does not freeze a multi-page scan. |

### 16.7 Follow the implementation

```text
EventOperationsCli reconcile
→ ProjectionReconciliationService.reconcile
→ ProjectionReconciliationRepository.snapshot / page / gaps
→ one read-only PostgreSQL snapshot
→ ProjectionReconciliationReport
```

Read the [Milestone 8 learning guide](MILESTONE_8_LEARNING_GUIDE.md) for detailed findings and the [reconciliation runbook](PROJECTION_RECONCILIATION_RUNBOOK.md) for commands. This layer provides the reviewed coverage and discrepancy evidence that an isolated rebuild needs.

## 17. Milestone 9: isolated shadow rebuilding and catch-up generations

### 17.1 Rebuilding needs another logical identity

Milestone 9 constructs a replacement candidate without weakening normal consumer deduplication. A **shadow projection** is a separately identified read model built beside the baseline. The run UUID deterministically produces `shadow-<run UUID>`; its receipts and projection rows use that consumer name in the existing tables. The permanent `payment-projection-v1` identity retains its receipts, diagnostics and financial separation.

A new Kafka group would only change broker progress. It would not change the logical receipt identity already stored in PostgreSQL. Deleting baseline receipts would discard accepted-event evidence. A generated shadow instead makes rebuilding explicit, inspectable and isolated.

The input is one retained current source event per payment. This is a **latest-state snapshot rebuild**, not replay of complete financial history. It neither calls capture/refund nor generates ledger postings, delivery acknowledgements or Kafka offsets. Missing older events and live quarantine obligations remain visible.

### 17.2 Freeze the work before processing batches

[`ProjectionRebuildService.create`](../src/main/java/com/fincore/payments/service/ProjectionRebuildService.java) creates a run and generation 0. [`ProjectionRebuildRepository`](../src/main/java/com/fincore/payments/repository/ProjectionRebuildRepository.java) materializes a manifest: durable per-payment source/version/event evidence, original payload, canonical decoded contract and classification. A **manifest** is the recorded set of inputs a generation promises to process.

The shared strict `PaymentEventRecordDecoder` validates these inputs using the same event contract as live consumption. A missing current event becomes `NO_CURRENT_SOURCE_EVENT`; invalid input or source disagreement becomes a frozen blocked item. Ignoring blocked legacy payments would make a partial result appear complete. Synthesizing an event from today's source row would invent historical identity.

Planning owns a `REQUIRES_NEW` Repeatable Read transaction with a 60-second timeout. All source candidates belong to the same snapshot, and the complete classified generation commits together. Bounded classification reads control temporary Java work; planning still scans and stores the complete candidate set.

### 17.3 Atomic checkpoints make interruption resumable

`ProjectionRebuildService.step` locks the run and selects up to 100 pending items. It re-decodes preserved input, checks canonical JSONB agreement, inserts a shadow receipt, applies the version-guarded projection and marks the item APPLIED. The generation's progress and terminal state commit in the same Read Committed transaction.

That shared commit is an **atomic checkpoint**. A process stopping before commit leaves neither the effects nor their progress marker. A stop after commit leaves both. A subsequent step continues remaining work without guessing which write finished. The run row lock serializes competing workers and catch-up changes for that run across processes.

An existing matching shadow receipt is insufficient by itself: the step also verifies that the shadow projection matches the promised input. A missing, drifted or unexpectedly advanced projection fails closed. V9 deferred database checks reinforce matching receipt/projection evidence for APPLIED items and consistent generation completion.

### 17.4 Catch-up creates new evidence instead of rewriting old plans

Suppose generation 0 freezes payment A at version 0. While it builds, A advances to version 1 and payment B is created. Finishing that generation correctly produces the older A snapshot. Validation reports source change; build completion alone does not establish present readiness.

`catchUp` creates generation 1 under the same shadow identity, now including A version 1 and B version 0. Older manifests remain immutable evidence. A fresh catch-up requires the observed expected generation and no pending work. This **compare-and-set** precondition prevents stale operator intent from silently replacing a newer approved plan.

Create/catch-up also have durable request identity. Generation 0 uses the run UUID; later catch-up request UUIDs are globally unique. An identical accepted retry returns the original plan generation/time, even after further progress. Changed intent under the same identity conflicts. A step has different semantics: repeating it may advance the next batch; it does not replay a saved batch response. Read status after an uncertain step response.

### 17.5 Retry the transaction, then validate the result

Repeatable Read planning can encounter serialization failure `40001` or deadlock `40P01`. The service permits at most three complete planning attempts. Each failed attempt rolls back; the next starts with a fresh snapshot and repeats state/request decisions. Retrying only the last SQL statement cannot revive an aborted transaction or replace its stale snapshot. The bounded loop applies to create/catch-up, not every rebuild method.

`validate` independently compares the manifest, shadow receipts/projections and current source. It detects pending/blocked inputs, shadow mismatch, source additions/removals/change and unplanned shadow rows. Recovery-gap context remains separate, and historical completeness stays `NOT_CERTIFIED`. A valid snapshot reconstruction cannot prove missing historical side effects occurred.

### 17.6 Choices and costs

| Choice | Alternative | Why it fits here, and its cost |
| --- | --- | --- |
| Generated logical consumer | Delete receipts or use another Kafka group | Preserves baseline deduplication and isolates artifacts; creates additional retained rows. |
| Frozen per-generation manifest | Requery changing source between batches | Makes promised inputs reproducible; planning and copied evidence consume time/storage. |
| Effects plus checkpoint in one transaction | Save a cursor before or after effects separately | Eliminates ambiguous partial progress; batch size and transaction duration need bounds. |
| Audited catch-up generation | Rewrite the original plan | Preserves why each plan existed; every generation adds retained evidence. |
| Request UUID plus expected generation | Blind refresh on retry | Distinguishes lost-response replay from new stale intent; command and status semantics need care. |

### 17.7 Ownership changes after Milestone 10

```text
ProjectionRebuildService.create / catchUp
→ frozen manifest COMMIT
→ ProjectionRebuildService.step
→ shadow receipt + projection + APPLIED checkpoint COMMIT
→ ProjectionRebuildService.validate
```

Before enrollment, authenticated promotion performs its own fresh checks. After enrollment, the run, generations and items are frozen; live fanout owns projection updates. A later projection can legitimately differ from its historical manifest. Validation identifies `ENROLLED_LIVE_FANOUT`, while route status describes current ownership. Continue with the [rebuild runbook](SHADOW_PROJECTION_REBUILD_RUNBOOK.md) and [Milestone 9 learning guide](MILESTONE_9_LEARNING_GUIDE.md), whose original milestone limits are marked historically.

## 18. Milestone 10: authenticated projection promotion and rollback

### 18.1 Turning a reviewed candidate into a maintained read target

Milestone 10 connects the shadow to readers. **Promotion** selects it; **rollback** selects the baseline again. Neither action reverses payment or ledger facts. First promotion requires both targets to be healthy, current and equivalent. It cannot repair a damaged baseline, because that would discard the preserved-read and verified-rollback guarantees.

The database enrolls **one lifetime shadow**. Baseline `payment-projection-v1` remains the permanent logical diagnostic identity. Enrollment survives rollback, and both targets keep receiving events. Another isolated run may be built for investigation, but replacing or retiring the enrolled target is future work. This bounds ongoing fanout instead of accumulating live historical targets.

### 18.2 Authentication establishes who made the decision

[`ProjectionOperationsSecurityConfiguration`](../src/main/java/com/fincore/payments/config/ProjectionOperationsSecurityConfiguration.java) protects only `/api/v1/operations/projection-routing` and its children. One configured username and BCrypt password verifier establish a `PROJECTION_OPERATOR`. No configured credentials means deny access; partial or invalid configuration fails servlet startup. Payment endpoints retain their existing public learning behavior, and the headless CLI does not acquire authenticated HTTP semantics.

HTTP Basic presents credentials; BCrypt verifies the password without storing it as plaintext. Basic still requires encrypted transport outside loopback. CSRF remains enabled: clients fetch an authenticated token, retain its session cookie and send both with POST. Authentication, role authorization and CSRF address different boundaries.

BCrypt is an adaptive password hash: its configurable work factor makes verification deliberately expensive. The fast SHA-256 fingerprints used for event/request comparison have a different job and are not this application's password storage mechanism. Basic credentials can be sent automatically by browsers, so removing a login form would not remove the reason for CSRF protection. The role decides which authenticated account may use these operations; the token checks the mutating request's session context.

[`ProjectionRoutingController.promote`](../src/main/java/com/fincore/payments/controller/ProjectionRoutingController.java) obtains the audit actor from `Principal.getName()`. A caller supplies a reason, not an authoritative operator name. Existing CLI labels remain local self-declarations under local execution/database access.

### 18.3 Fresh checks need a protected decision window

An earlier rebuild validation can become stale before cutover. [`ProjectionRoutingService.promote`](../src/main/java/com/fincore/payments/service/ProjectionRoutingService.java) and `rollback` therefore own fresh `REQUIRES_NEW` Read Committed transactions with 30-second timeouts. They suspend an existing caller transaction rather than inherit its old snapshot. An accepted decision commits independently of that caller's eventual outcome.

[`ProjectionRoutingRepository.fence`](../src/main/java/com/fincore/payments/repository/ProjectionRoutingRepository.java) first tries an exclusive transaction advisory lock. Consumer/rebuild mutations use its shared counterpart. It then acquires the relevant source, outbox, rebuild, consumer and incident tables in `SHARE` mode with `NOWAIT`. Comparisons begin after all freezes succeed, so Read Committed sees their latest committed facts. Busy work produces a conflict rather than waiting in a cycle with existing position/source locks.

First enrollment requires a completed unblocked generation and fresh manifest/source/shadow agreement. Every switch also checks both targets against current source/outbox/receipt content, against each other, and against known open recovery gaps across source scopes. Equivalence includes source-less consumer records: ignoring them could make an existing read disappear when switching to a source-backed shadow.

The conservative freeze briefly excludes writes and can require full-view scans. It establishes an inspectable local-database boundary, not a production throughput or eventual-progress guarantee under continuous activity.

### 18.4 Consumer transactions keep both targets warm

`PaymentEventConsumer.consume` retains `REQUIRED` propagation so its caller, `ConsumerRecordProcessor.process`, commits position/success resolution with receipts and projections atomically. Starting an independent consumer transaction would break that relationship. Instead, `PaymentEventConsumerRepository.requireReadCommitted` checks the actual JDBC connection before writes and rejects inherited Repeatable Read/Serializable isolation that could miss new enrollment.

After the shared gate, the consumer visits baseline and enrolled shadow in one transaction. Even an already accepted baseline duplicate visits the warm target. Version guards preserve the newest snapshot; matching latest projection fields and canonical receipt content are then required for the affected payment. A discrepancy aborts processing and follows existing failure handling rather than silently repairing evidence.

Enrollment also changes `projection_rebuild_runs.routing_enrolled`. A stale Repeatable Read catch-up locking that changed run receives a serialization failure; whole-transaction retry sees enrollment and rejects mutation. Database guards freeze enrolled build metadata. This closes a race that a route lookup alone could miss.

### 18.5 Routing, revisions and exact replay

`PaymentEventConsumerRepository.findRoutedProjection` selects route and projection in one SQL statement, giving one committed statement snapshot. A request overlapping cutover can observe the complete old or new committed view. Subsequent source writes still reach both views asynchronously; promotion does not make them synchronous with payment capture.

Each new decision supplies an expected route revision, request UUID and reason; promotion additionally names the run and expected build generation. The immutable audit and pointer revision commit together under V10 deferred consistency guards. Exact accepted replay requires the same actor/action/reason/preconditions and returns its original result. A promotion retried after rollback does not promote again; status separately shows today's route. Replay still takes the fence and can be temporarily busy.

The wire preconditions must also be precise. [StrictRoutingCounterDeserializer](../src/main/java/com/fincore/payments/config/StrictRoutingCounterDeserializer.java) accepts explicit integral JSON numbers, rejecting nulls, numeric strings, decimals and overflow. Required-field declarations prevent omitted counters from silently becoming zero. This preserves the operator's submitted revision/generation intent before the service checks it against current database state; strict parsing does not replace that transactional comparison.

Rollback requires current equivalence and freshness again. Re-promotion selects the same enrolled run and frozen generation with a fresh request/current revision. A later live projection may exceed its old manifest: that manifest remains historical build evidence, not a continuously current readiness certificate.

### 18.6 Failure example and alternatives

A shadow validates at source version 1; capture then commits version 2 before promotion. The fenced switch detects stale facts and refuses. Ordinary consumption and pre-enrollment catch-up must restore agreement. After enrollment, a valid version-3 event updates both targets atomically; rollback then selects the maintained baseline without erasing version 3.

| Choice | Alternative | Why it fits here, and its cost |
| --- | --- | --- |
| Fresh fenced transaction | Trust the last validation response | Closes the check/switch window; broad locking can cause busy refusals. |
| One persistent route and one-statement read | Process flag or separate pointer/data reads | Shares committed ownership across readers; depends on database/query correctness. |
| Maintain baseline and one shadow atomically | Stop the old target or fan out forever | Supports checked rollback with bounded targets; adds applicable receipt/projection work. |
| Principal, BCrypt, role and CSRF | Caller-supplied operator or custom authentication | Uses established framework boundaries; multiple-user/federated identity remains future work. |
| Revision plus request identity | Blindly retry a switch | Separates outdated intent from accepted replay; clients must preserve the original command. |

### 18.7 Follow the implementation

```text
POST /api/v1/operations/projection-routing/promote
→ Spring Security → ProjectionRoutingController.promote
→ ProjectionRoutingService.promote → ProjectionRoutingRepository.fence
→ fresh checks → switchRoute → audit + enrollment + route COMMIT
```

Use the [routing runbook](PROJECTION_ROUTING_RUNBOOK.md) for commands and the [Milestone 10 learning guide](MILESTONE_10_LEARNING_GUIDE.md) for deeper races and security details. The next milestone must define controlled target replacement/retirement, including coverage, ownership and retention.

## 19. The next implementation

### 19.1 Proposed next block: controlled target replacement and retirement

**Not implemented yet.** Define how a new isolated run catches live changes, establishes required source-less coverage, adopts maintenance and replaces the one enrolled shadow without losing request/audit guarantees. Define when old target data can retire and which evidence must survive. Keep active ownership, historical build evidence and routing revision distinct.

Measure comparison cost, lock contention, transaction duration and storage growth on representative data; establish operational limits before adding automatic orchestration. A constantly busy database may refuse nonwaiting route changes indefinitely. Tenant/merchant access control and richer operator identity remain separate from this scoped learning boundary.

**Completeness versus availability:** quarantining and advancing an offset can keep a partition moving but leave a gap. A later full snapshot may repair a latest-state view; it cannot establish that every skipped event was safe for every future financial/notification consumer. Use consumer-specific policy.

**Retention and rebuild:** source events, broker retention, event receipts, diagnostic position markers, and rebuild lifetime must be planned together. Deleting deduplication or success evidence too early can reopen effects or stale incidents. A fresh Kafka group alone does not rebuild the logical consumer's data; a new identity/isolated projection may be needed.

**Recovery identity:** continue preserving audit history across retry cycles. A replay of an accepted operator request is different from a newly approved repair attempt. Keep state/generation preconditions and immutable decision records.

**Scope control:** keep the row-lock relay unless measurements justify leases. More infrastructure does not automatically provide recovery ownership or an authorization boundary.

### 19.2 Later milestones

Possible subsequent work includes merchant identity and access control, tenant-scoped idempotency, provider integration, partial capture/refund and fees, settlement reconciliation, webhooks, richer read models, automated retention/rebuild orchestration, production Kafka security/availability, and separate consumer deployment. Their order should follow a business objective. They are not existing functionality.

## 20. Code and API map

### 20.1 Read the code in this order

Paths below are relative to the project. Links open the actual files; related classes live alongside them.

| Read | Why |
| --- | --- |
| [pom.xml](../pom.xml), [application.yml](../src/main/resources/application.yml) | Runtime, dependency management, database/transport defaults |
| [PaymentController.java](../src/main/java/com/fincore/payments/controller/PaymentController.java) and [CreatePaymentRequest.java](../src/main/java/com/fincore/payments/dto/CreatePaymentRequest.java) | HTTP boundary and validation |
| [Payment.java](../src/main/java/com/fincore/payments/domain/Payment.java) | Invariants, state machine, version field |
| [PaymentService.java](../src/main/java/com/fincore/payments/service/PaymentService.java) | Main transaction orchestration and flush order |
| [PaymentRequestFingerprint.java](../src/main/java/com/fincore/payments/service/PaymentRequestFingerprint.java), [IdempotencyRepository.java](../src/main/java/com/fincore/payments/repository/IdempotencyRepository.java) | Logical input identity and concurrent claims |
| [LedgerTransaction.java](../src/main/java/com/fincore/payments/domain/LedgerTransaction.java), [LedgerService.java](../src/main/java/com/fincore/payments/service/LedgerService.java), [LedgerRepository.java](../src/main/java/com/fincore/payments/repository/LedgerRepository.java) | Balanced journals, shared transaction, derived totals |
| [V1](../src/main/resources/db/migration/V1__create_payments_table.sql), [V2](../src/main/resources/db/migration/V2__create_idempotency_records.sql), [V3](../src/main/resources/db/migration/V3__create_double_entry_ledger.sql) | Payment/idempotency/ledger schema, constraints, backfill |
| [PaymentEvent.java](../src/main/java/com/fincore/payments/domain/PaymentEvent.java), [OutboxService.java](../src/main/java/com/fincore/payments/service/OutboxService.java) | Immutable event creation inside source transaction |
| [OutboxDispatcher.java](../src/main/java/com/fincore/payments/service/OutboxDispatcher.java), [OutboxDeliveryWorker.java](../src/main/java/com/fincore/payments/service/OutboxDeliveryWorker.java), [OutboxFailureRecorder.java](../src/main/java/com/fincore/payments/service/OutboxFailureRecorder.java) | Delivery and post-rollback retry boundaries |
| [OutboxRepository.java](../src/main/java/com/fincore/payments/repository/OutboxRepository.java), [V4](../src/main/resources/db/migration/V4__create_payment_outbox.sql) | Ordering, row locks, metadata, local sink |
| [KafkaEventConfiguration.java](../src/main/java/com/fincore/payments/config/KafkaEventConfiguration.java), [KafkaPaymentEventPublisher.java](../src/main/java/com/fincore/payments/service/KafkaPaymentEventPublisher.java) | Broker settings and acknowledgement wait |
| [KafkaPaymentEventListener.java](../src/main/java/com/fincore/payments/service/KafkaPaymentEventListener.java), [PaymentEventValidator.java](../src/main/java/com/fincore/payments/service/PaymentEventValidator.java) | Wire contract and validation |
| [PaymentEventConsumer.java](../src/main/java/com/fincore/payments/service/PaymentEventConsumer.java), [PaymentEventConsumerRepository.java](../src/main/java/com/fincore/payments/repository/PaymentEventConsumerRepository.java), [V5](../src/main/resources/db/migration/V5__create_payment_event_consumer.sql) | Durable deduplication and projection transaction |
| [EventOperationsCli.java](../src/main/java/com/fincore/payments/operations/EventOperationsCli.java), [EventOperationsService.java](../src/main/java/com/fincore/payments/service/EventOperationsService.java), [EventOperationsRepository.java](../src/main/java/com/fincore/payments/repository/EventOperationsRepository.java), [V6](../src/main/resources/db/migration/V6__add_outbox_operations.sql) | Local operator workflow, cycle budgets, request replay, atomic recovery audit |
| [ConsumerFailureService.java](../src/main/java/com/fincore/payments/service/ConsumerFailureService.java), [ConsumerFailureRepository.java](../src/main/java/com/fincore/payments/repository/ConsumerFailureRepository.java), [V7](../src/main/resources/db/migration/V7__add_consumer_failure_tracking.sql) | Durable failure evidence and source-position success markers |
| [ConsumerRecoveryService.java](../src/main/java/com/fincore/payments/service/ConsumerRecoveryService.java), [ConsumerRecoveryRepository.java](../src/main/java/com/fincore/payments/repository/ConsumerRecoveryRepository.java), [V8](../src/main/resources/db/migration/V8__add_consumer_recovery.sql) | Explicit quarantine, known gaps, idempotent original replay and immutable audit |
| [ConsumerRecordProcessor.java](../src/main/java/com/fincore/payments/service/ConsumerRecordProcessor.java), [PaymentEventRecordDecoder.java](../src/main/java/com/fincore/payments/service/PaymentEventRecordDecoder.java) | Shared position lock, strict live/replay wire contract and atomic processing/success |
| [ProjectionReconciliationService.java](../src/main/java/com/fincore/payments/service/ProjectionReconciliationService.java), [ProjectionReconciliationRepository.java](../src/main/java/com/fincore/payments/repository/ProjectionReconciliationRepository.java) | Read-only repeatable snapshot, source/consumer comparisons, scope/coverage and pagination |
| [ProjectionRebuildService.java](../src/main/java/com/fincore/payments/service/ProjectionRebuildService.java), [ProjectionRebuildRepository.java](../src/main/java/com/fincore/payments/repository/ProjectionRebuildRepository.java), [V9](../src/main/resources/db/migration/V9__add_shadow_projection_rebuilds.sql) | Isolated manifest generations, durable checkpoints, catch-up identity and validation |
| [ProjectionRoutingController.java](../src/main/java/com/fincore/payments/controller/ProjectionRoutingController.java), [ProjectionOperationsSecurityConfiguration.java](../src/main/java/com/fincore/payments/config/ProjectionOperationsSecurityConfiguration.java) | Scoped authenticated principal/role, CSRF and routing HTTP contract |
| [ProjectionRoutingService.java](../src/main/java/com/fincore/payments/service/ProjectionRoutingService.java), [ProjectionRoutingRepository.java](../src/main/java/com/fincore/payments/repository/ProjectionRoutingRepository.java), [V10](../src/main/resources/db/migration/V10__add_projection_routing.sql) | Freshness fence, equivalence, accepted replay, enrollment, revision and immutable routing history |
| [KafkaLagService.java](../src/main/java/com/fincore/payments/service/KafkaLagService.java), [EventOperationsDatabaseTests.java](../src/test/java/com/fincore/payments/integration/EventOperationsDatabaseTests.java), [LocalEventOperationsCliIT.java](../src/test/java/com/fincore/payments/operations/LocalEventOperationsCliIT.java) | Bounded progress observation and recovery/launcher verification |
| [GlobalExceptionHandler.java](../src/main/java/com/fincore/payments/exception/GlobalExceptionHandler.java) | Stable HTTP error contract |
| [IdempotencyDatabaseTests.java](../src/test/java/com/fincore/payments/integration/IdempotencyDatabaseTests.java), [LedgerDatabaseTests.java](../src/test/java/com/fincore/payments/integration/LedgerDatabaseTests.java), [OutboxDatabaseTests.java](../src/test/java/com/fincore/payments/integration/OutboxDatabaseTests.java) | Database-level failure and race evidence |
| [KafkaEventDatabaseTests.java](../src/test/java/com/fincore/payments/integration/KafkaEventDatabaseTests.java), [KafkaBrokerTests.java](../src/test/java/com/fincore/payments/integration/KafkaBrokerTests.java) | Receipt/projection and real broker/offset failure evidence |

### 20.2 Existing HTTP surface

| Method | Endpoint | Use |
| --- | --- | --- |
| POST | `/api/v1/payments` | Create or replay creation with Idempotency-Key |
| GET | `/api/v1/payments/{paymentId}` | Authoritative current payment |
| POST | `/api/v1/payments/{paymentId}/authorize` | Apply allowed authorization transition |
| POST | `/api/v1/payments/{paymentId}/capture` | Capture plus journal plus event |
| POST | `/api/v1/payments/{paymentId}/fail` | Apply allowed failure transition |
| POST | `/api/v1/payments/{paymentId}/refund` | Full refund plus reversal plus event |
| GET | `/api/v1/payments/{paymentId}/ledger` | Immutable accounting history |
| GET | `/api/v1/ledger/accounts/{currency}/balances` | Currency-specific derived totals |
| GET | `/api/v1/payments/{paymentId}/events` | Source events and delivery metadata |
| GET | `/api/v1/payments/{paymentId}/projection` | Latest active routed consumer snapshot, if available |
| GET | `/api/v1/operations/projection-routing` | Authenticated route revision and ownership |
| GET | `/api/v1/operations/projection-routing/csrf` | Authenticated CSRF token; retain the session cookie |
| POST | `/api/v1/operations/projection-routing/promote` | Authenticated, CSRF-protected first enrollment or same-shadow re-promotion |
| POST | `/api/v1/operations/projection-routing/rollback` | Authenticated, CSRF-protected checked baseline selection |

There is no HTTP dispatcher trigger, DEAD requeue, lag, quarantine, or projection-rebuild endpoint today. The local operations CLI supplies outbox reads, lag/failure/gap/reconciliation reports, audit history, audited DEAD requeue, explicit consumer quarantine/original replay, and isolated shadow rebuilding/catch-up; see [source operations](EVENT_OPERATIONS_RUNBOOK.md), [consumer recovery](CONSUMER_RECOVERY_RUNBOOK.md), [reconciliation](PROJECTION_RECONCILIATION_RUNBOOK.md) and [shadow rebuilding](SHADOW_PROJECTION_REBUILD_RUNBOOK.md). Promotion/rollback uses the separate authenticated [routing HTTP surface](PROJECTION_ROUTING_RUNBOOK.md), not a new CLI switch.

## 21. Study path and reference material

### 21.1 Learn by explaining these examples

Work through the questions in order. Use sections 4–11 and 14–18 and the linked tests to check your explanation.

1. Trace a valid HTTP request through controller, DTO, service, domain, repository, and transaction.
2. Explain why a flushed payment can still roll back, and why a separate proxied consumer bean matters.
3. Explain the difference between two stale captures, two identical creation retries, and two deliveries of one event.
4. Draw the two capture entries and refund reversal; calculate both normal-side balances.
5. Describe exactly what remains durable when Kafka accepts an event but outbox acknowledgement rolls back.
6. Describe exactly what happens when the consumer database commits but its Kafka offset does not.
7. Explain why local delivery does not create a projection, and why a new Kafka group does not automatically rebuild one.
8. Design a safe recovery of a DEAD source event without changing its event identity or original payload.
9. Explain why a validated shadow is insufficient for cutover, and trace the locks, fresh comparisons and atomic audit/pointer commit.
10. Explain how rollback preserves a newer consumed fact, why baseline duplicates still visit the shadow and why an enrolled run stops accepting catch-up mutation.
11. Distinguish an authenticated principal, authorization role, BCrypt verifier, Basic credential and session-backed CSRF token.

### 21.2 Official references used for the explanations

Project behavior is established by the linked code and migrations, not by a generic tutorial. These primary references explain the platform semantics behind it:

- [Java 21 BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html) — decimal scale, equality, and rounding.
- [PostgreSQL 17 numeric types](https://www.postgresql.org/docs/17/datatype-numeric.html) — exact decimal storage.
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html) — statement snapshots and concurrent operations.
- [PostgreSQL constraints](https://www.postgresql.org/docs/17/ddl-constraints.html) — uniqueness, foreign keys, and CHECK semantics.
- [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html) — row ownership and conflicting operations.
- [Spring Framework 6.2 transactions](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html) — transaction annotations and proxies.
- [Kafka 3.9 producer configuration](https://kafka.apache.org/39/configuration/producer-configs/) — acknowledgements, idempotence, and timeout settings.
- [Spring Kafka 3.3 listener containers](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html) — concurrency and offset acknowledgement.
- [Spring Kafka 3.3 error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html) — retry and recovery behavior.
- [PostgreSQL advisory lock functions](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS) — shared/exclusive transaction coordination.
- [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html) and [Basic authentication](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/basic.html) — token/session and principal boundaries.
- [RabbitMQ streams](https://www.rabbitmq.com/docs/streams) — why replay is not an exclusive Kafka capability.

### TOPICS TO LEARN FROM ALL SESSIONS

- Java records, enums, immutability, UUIDs, and UTC instants
- Spring Boot, dependency injection, configuration, and Maven dependency management
- REST contracts, DTOs, Bean Validation, and exception handling
- Domain state machines and business invariants
- BigDecimal, numeric precision, scale, and exact JSON parsing
- JPA/Hibernate, dirty checking, JDBC, and persistence boundaries
- Transactions, proxies, propagation, flush, commit, rollback, and isolation
- Optimistic locking, row locks, uniqueness, and compare-and-set updates
- Flyway migrations, constraints, deferred checks, indexes, and backfills
- Request idempotency, SHA-256 fingerprints, and stored response snapshots
- Double-entry accounting, normal balances, immutable journals, and reversals
- Transactional outbox, SKIP LOCKED, ordering, backoff, and DEAD events
- Kafka topics, partitions, keys, consumer groups, offsets, and acknowledgements
- Producer idempotence versus durable consumer idempotency
- At-least-once delivery and cross-system crash windows
- Projection upserts, full snapshots, eventual consistency, and CQRS-like separation
- Poison records, quarantine, recovery, replay, and retention
- JUnit, Mockito, MockMvc, Testcontainers, and real-service fault testing
- Operational visibility, lag, auditability, and safe recovery ownership
- Retry generations, cycle budgets, idempotent operator requests, and atomic recovery audits
- Durable source-position fingerprints and stale-diagnostic suppression
- MVCC, Repeatable Read observations, keyset pagination and snapshot lifetime
- Projection reconciliation, logical consumer scope and historical coverage
- Shadow identities, frozen manifests, generations and atomic checkpoints
- Complete-transaction retry, TransactionTemplate and serialization failures
- Catch-up validation, freshness fencing and authenticated promotion/rollback
- Advisory transaction locks, SHARE NOWAIT and stale-planner serialization
- Transaction ownership, REQUIRED/REQUIRES_NEW and actual JDBC isolation
- Atomic read routing, one-time enrollment and maintained rollback targets
- Revision CAS, accepted-request replay and immutable route decisions
- Spring Security filter chains, principals, roles, BCrypt, Basic, TLS and CSRF sessions
- Cached Spring test contexts, Hikari connection pools and resource budgets

## 22. Milestone 19 — Reliable outgoing merchant webhooks

Milestone 19 adds tenant-owned callback subscriptions for future payment events. An `AFTER INSERT` trigger on `payment_outbox` snapshots an immutable body into a delivery row in the same transaction, avoiding a crash gap between the payment commit and notification enrollment. `MerchantWebhookCrypto` uses per-subscription HMAC-SHA256 signatures and AES-256-GCM encryption for the signing secret. Literal loopback destination allowlisting is a deliberately narrow SSRF boundary for this sandbox.

`MerchantWebhookTransactions.claimNext` and `finish` own short database transactions around a lease-token claim and outcome recording. `MerchantWebhookDispatcher` performs bounded HTTP between them. PostgreSQL `SKIP LOCKED`, immutable attempt rows, a capped retry budget and explicit DEAD/manual-retry generations support parallel workers and crash recovery. Events for a given payment/subscription stay ordered; a DEAD predecessor blocks successors until repaired. Delivery is at least once, so the receiver deduplicates stable delivery IDs and exact bodies; no network design can guarantee exactly-once downstream side effects.

The alternatives, key-rotation/egress limits, failure examples, runnable demo, actual request flow and verification detail are in the [Milestone 19 guide](MILESTONE_19_LEARNING_GUIDE.md), [runbook](MERCHANT_WEBHOOK_RUNBOOK.md) and [summary](MILESTONE_19_SUMMARY.md). Six proposed milestones remain (20–25), beginning with partial operations and fee accounting. The guide remains a learning sandbox and does not claim public egress or production readiness.
## 23. Milestone 20 — Partial payment operations and explicit fees

Milestone 20 introduces a permanent amount-command mode for manually authorized local payments before their first capture. Capture totals stay within authorization; each refund names one capture and stays within that capture's remaining principal. The first refund closes further capture. Capture commands carry an explicit retained fee; refunds return principal and do not reverse the fee. A separate fee journal debits merchant payable and credits platform fee revenue. Full principal refund can therefore leave a negative merchant payable equal to retained fees.

`PaymentAccountingService.submit` owns a new transaction around source-row locking, request/version validation, immutable command acceptance, JPA payment flush, principal/fee journals and the existing outbox/merchant enrollment. Deferred database checks bind these records together. UUID replay reconstructs its historical snapshot from immutable command prefixes; the current accounting read derives totals from committed ledger headers. Two new lifecycle statuses preserve partial state in the established event/projection path. The snapshot's original amount still means authorization; the accounting API exposes capture/refund/fee totals.

Read the [Milestone 20 guide](MILESTONE_20_LEARNING_GUIDE.md), [runnable accounting flow](PAYMENT_ACCOUNTING_RUNBOOK.md) and [summary](MILESTONE_20_SUMMARY.md) for the worked journal example, alternatives, concurrency/rollback evidence and limits. The 100-command teaching cap, retained fees, capture closure and full-amount provider protocol are explicit boundaries. Next is independent settlement reconciliation; five proposed blocks remain, 21–25. The Word edition remains the Milestones 1–10 snapshot.
