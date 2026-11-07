# Fincore payments complete learning guide

**Latest continuation (2026-10-28):** [Milestone25](MILESTONE_25_LEARNING_GUIDE.md) adds verified release/deployment inputs and packaged end-to-end sandbox acceptance. Use the [final project map](FINAL_PROJECT_GUIDE.md), [deployment runbook](DEPLOYMENT_RUNBOOK.md), [final validation report](FINAL_VALIDATION_REPORT.md) and [summary](MILESTONE_25_SUMMARY.md). All25 planned functionality blocks are implemented; Docker/real Redis combined execution is the next environment-validation action. Older next-step notes below and historical Word volumes retain their dated scope.

**Latest continuation (2026-10-27):** [Milestone 24 — logical database recovery](MILESTONE_24_LEARNING_GUIDE.md) adds consistent snapshot-backed archives, read-only isolated-restore verification and actual recovered API checks. Use the [runbook](DATABASE_RECOVERY_RUNBOOK.md), [retention decision](RETENTION_POLICY.md) and [summary](MILESTONE_24_SUMMARY.md). M25 deployment/final validation is the one remaining planned block. Older next-step notes below and Word volumes retain their historical scope.

**Latest continuation (2026-10-27):** [Milestone 23 — payment observability](MILESTONE_23_LEARNING_GUIDE.md) adds private observer credentials, bounded metrics/traces, sampled source backlogs and freshness-aware alerts. Read its [runbook](OBSERVABILITY_RUNBOOK.md) and [verification summary](MILESTONE_23_SUMMARY.md). M24 restore/operational closure is next; two planned blocks remain through M25. Older next-step statements below are historical, and the Word volumes retain their original scope.

**Latest continuation (2026-10-27):** [Milestone 22](MILESTONE_22_LEARNING_GUIDE.md) adds opt-in Redis creation quotas, deterministic amount/currency rules, immutable risk evidence and explicit cross-store outage/retry behavior. Its [summary](MILESTONE_22_SUMMARY.md) identifies real Redis checks that could not run here. This volume retains its historical scope through M20; see the [roadmap](ROADMAP.md) for current status.

**Continuation beyond this volume (2026-10-27):** [Milestone 21](MILESTONE_21_LEARNING_GUIDE.md) implements independent signed sandbox settlement statements, immutable discrepancy reports and fresh same-statement closure. Use its [runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md) and [verification summary](MILESTONE_21_SUMMARY.md). This volume and its Word edition retain their historical scope through M20; the [roadmap](ROADMAP.md) records current status and proposed M22–25.

**Milestones 11 through 20**

**Updated:** 27 October 2026. Implementation and test evidence is recorded through 26 October 2026.

## 1 Purpose and how to use this continuation

This is the second volume of the learning guide for the independent Java project `fincore-payments`. It continues the Milestones 1–10 guide and explains how the project developed from a reliable local payment and event pipeline into a merchant-owned sandbox with provider interaction, callbacks, reliable merchant notifications and operation-level accounting. It also explains the operational evidence needed when replacing derived read models.

We are building this project to understand backend payment engineering through implemented failure cases: retries after lost responses, concurrent commands, transaction rollback, duplicate events, uncertain remote outcomes, tenant isolation and auditable recovery. The objective is a demonstrable distributed payment learning platform. Completing a lifecycle status alone is insufficient; we want to explain which system committed, which identities make retries safe and which evidence supports a decision.

This volume uses the same teaching pattern as the first: identify the problem, explain the concept, show its actual role in the code, work through an example, examine failure, and compare the chosen approach with alternatives and their costs. Chapters 5–14 develop each milestone. The surrounding chapters connect them and provide a code map, recovery questions, verification evidence and the next proposed implementation.

The implementation is a local sandbox. The provider process makes independent test decisions over HTTP; the ledger models obligations. No live vendor, bank payout or real funds transfer has been added. Local partial accounting and full provider operations are separate supported modes. The current status is Milestones 1–20 implemented, with proposed blocks 21–25 remaining. That count is a planning boundary rather than a percentage of effort or production readiness.

### Read the two volumes together

The first volume establishes Spring Boot request handling, DTOs, validation, JPA/Hibernate, PostgreSQL, Flyway, BigDecimal, payment state transitions, idempotency, journals, outbox delivery, Kafka, consumer receipts, quarantine, reconciliation, isolated rebuilding and authenticated routing. This volume builds on those concepts. Read [the first volume source](COMPLETE_LEARNING_GUIDE.md) or its [Word edition](FINCORE_PAYMENTS_COMPLETE_LEARNING_GUIDE_MILESTONES_1_TO_10.docx) when a foundation needs revision.

Treat the earlier guide as a historical snapshot. Merchant HTTP endpoints now require the M16 merchant principal, mutating merchant requests retain CSRF protection, M18 adds a separately authenticated machine callback route, and M20 introduces partial lifecycle values. The earlier statement that there is no provider integration describes the earlier release; M17 subsequently added the local sandbox integration.

For conceptual study, read chapters 2–4, then the ten milestone chapters, then chapters 15–21. For a concrete task, start from the relevant runbook linked in chapter 20. Runbooks contain executable requests and required environment configuration; this guide explains why those requests work. The named source classes and migrations are repository evidence, while the worked scenarios are teaching examples unless explicitly identified as executed tests.

Alternative comparisons are contextual. OAuth, a managed queue, asynchronous inbox processing, KMS and other designs can be appropriate at a different scale. We explain why their additional contracts were unnecessary or unsupported at this stage, while recording the costs of the simpler choice. “Not selected here” does not mean universally inferior.

## 2 How the ten milestones connect

The work has two connected tracks. Milestones 11–15 make the lifecycle and review of derived-data evidence explicit. Milestones 16–20 develop the merchant payment path across authentication, remote uncertainty, incoming results, outgoing notifications and richer accounting. The evidence track protects our understanding of the event/read-model system; the payment track introduces new authoritative facts that that system must handle correctly.

| Milestone | Main addition | Question it answers |
| --- | --- | --- |
| 11 | Replace and retire read-model targets | How can we change maintenance ownership without losing the meaning of old receipts and decisions? |
| 12 | Evidence inventory and operating bounds | What remains for a retired target, and how much new comparison work will we admit? |
| 13 | Immutable bounded purge plans | Which exact artifacts were named, and do those fingerprints still match? |
| 14 | Verified preservation and review approval | Can we retain the full named bodies and attribute review of one exact copy? |
| 15 | Approval expiry, revocation and offline verification | Is an old approval current, and can a downloaded copy be checked independently of the server? |
| 16 | Merchant authentication and ownership | Who is calling, and which payment, retry key or ledger read may they access? |
| 17 | Durable sandbox provider commands | What happens when the remote system commits but our response is lost? |
| 18 | Verified incoming provider callbacks | Can a later authenticated result safely finish an existing command once? |
| 19 | Reliable outgoing merchant webhooks | Can a merchant learn about committed changes despite delivery failures? |
| 20 | Partial captures, allocated refunds and fees | What financial amounts and journals explain repeated operations on one payment? |

### What the operational track contributes

A projection is derived data, but its processing history is evidence. If we delete receipts when a target is retired, an old event can appear new on replay. If we reuse a retired identity for a new build, an old route audit may appear to describe the new contents. M11 keeps identities permanent. M12 inventories retained evidence. M13 freezes an explicit plan. M14 retains full bodies rather than only their hashes. M15 distinguishes the old acceptance from present validity and adds a standalone integrity checker.

These steps deliberately stop before deletion. Every review still reports `deletionPermitted=false`. There is no physical purge executor or restoration/import facility. Retirement releases maintenance; a plan records intent; a preservation copy records bytes; an approval records acknowledgement. Each has a narrower meaning than irreversible deletion authority.

### What the payment track contributes

M16 makes the merchant a durable part of payment ownership and command identity. M17 introduces a second committing system, so a network failure can no longer be interpreted as a business decline. M18 allows authenticated callbacks to resolve the existing frozen intent. M19 ensures that a committed event also creates durable merchant notification intent. M20 allows several local financial operations while preserving the payment, journal, event and notification transaction boundary.

An accounting operation can now produce a principal journal, a fee journal, a new payment version, an outbox event and enrolled merchant deliveries in one PostgreSQL commit. Later Kafka consumption and HTTP delivery each have their own commits and retries. The architecture therefore contains a strong local atomic boundary surrounded by explicitly repeatable distributed work.

## 3 Architecture boundaries and shared concepts

### Authority and derived state

Payments and immutable journals describe local business state and accounting. The outbox records payment events in the source transaction. Consumer receipts record accepted event identity, and projections provide a derived latest view. Routing chooses which maintained view serves reads. Provider commands represent frozen remote intent. Callback receipts represent received results. Merchant deliveries represent notification obligations. None of these records substitutes for every other kind of evidence.

```text
Merchant principal
  → owned payment command
  → PostgreSQL payment + journals + outbox + enrolled merchant delivery
       → outbox publisher → Kafka → receipt + baseline and maintained shadow
       → merchant dispatcher → signed HTTP → merchant receiver

Provider command prepare and claim → commit
  → sandbox HTTP decision → remote journal commit
  → local confirmed finish or incoming verified callback
  → PostgreSQL payment + journals + outbox in one completion transaction
```

The two network paths are independent. A merchant webhook retry does not repeat a capture. A Kafka replay does not ask the provider to capture again. A provider retry repeats the same remote operation identity rather than creating a new financial intent. This separation is the reason the project has several durable record types and counters.

### Local atomicity and distributed uncertainty

PostgreSQL can commit the local payment, journal, event and delivery intent together. It cannot atomically commit an arbitrary provider or merchant HTTP receiver. Holding a database transaction open during the call would retain resources without closing that gap. M17 and M19 instead use short transactions before and after network I/O. Recovery accepts that a send may repeat and uses stable operation/delivery identity to prevent repeated effects.

A lease says which worker may finish a local attempt. A fencing token rejects a late worker after ownership changes. Neither can stop a request already executing remotely. The provider's durable idempotency key or the merchant receiver's durable delivery deduplication is the other half of the design. Local fencing and remote deduplication solve different races.

### Transaction propagation and observation

`REQUIRES_NEW` gives a proxied service operation its own transaction and commit independent of an inherited caller. `NOT_SUPPORTED` suspends the caller during orchestration; it does not release the caller's retained locks or connection. `MANDATORY` requires a caller-owned transaction when shared financial application must commit with a provider command or callback receipt. These effects depend on the actual Spring proxy boundary, which is why separate transaction beans matter.

Read Committed is used for short decisions with explicit locks and fences. Read-only Repeatable Read is used where several queries must describe one observation, such as evidence review or accounting totals. A coherent report is immediately historical: it cannot authorize a later consequential write without that write's own checks. A sequence of keyset pages also has separate snapshots; it is not a single frozen export.

### Invariants in Java and PostgreSQL

Java performs validation and returns useful errors. PostgreSQL uniqueness, foreign keys, checks, immutable-row guards and deferred constraint triggers enforce the permitted final transaction shape. Deferred checks are valuable when a command, journal and resulting event refer to one another and are inserted at different points within the same transaction. A service method may finish its body and still fail at commit.

The constraints protect supported writers under the installed schema. A database administrator who can disable triggers or replace functions can bypass that boundary. Independent backup, privilege controls, restore drills and deployment discipline therefore remain necessary. Migrations are additive: earlier applied Flyway files are preserved rather than rewritten to pretend new rules always existed.

### Exact bytes and logical equality

Not every comparison uses the same equality rule. M20 money inputs normalize numerically equal decimal values into exact two-decimal intent. Consumer envelope checks use the documented logical/canonical representation. A signed callback and a preservation file use their exact UTF-8 bytes. Pretty-printing an otherwise equivalent JSON body changes a whole-file hash or HMAC input.

SHA-256 is an unkeyed digest for detecting changes against trusted reference values. HMAC uses a shared secret to authenticate a framed body. AES-GCM encrypts a subscription secret and authenticates its associated context. BCrypt verifies a password without retaining the password itself. These operations have distinct purposes; none supplies key rotation, external durability or provenance automatically.

## 4 Identities replay and a connected worked story

### Which identity protects which operation

| Identity | Kept stable for | Changes when |
| --- | --- | --- |
| Merchant plus creation key | Retrying one payment creation | A deliberate new creation is requested |
| Payment version | Comparing the source observation for new intent | A payment transition or amount operation commits |
| Operator request UUID | Recovering one accepted route or evidence decision | A new decision is requested |
| Route revision | Detecting another routing decision | A route lifecycle decision commits |
| Shadow run and generation | Naming a target and bounded rebuild work | A new run or catch-up generation is planned |
| Provider operation UUID | Repeating the same remote financial intent | A distinct accepted command is created |
| Provider instance UUID | Identifying the sandbox decision namespace | A genuinely new independent provider history is created |
| Provider event UUID | Deduplicating one incoming result body | A distinct provider event is emitted |
| Merchant delivery UUID | Repeating one subscription notification | A different event/subscription delivery is enrolled |
| Lease token | Fencing completion by one worker attempt | Another attempt acquires ownership |
| Amount request UUID | Recovering one capture or refund result | A distinct financial command is deliberately requested |

An accepted retry and a new request are different operations. The retry recovers the old accepted response even after later state changes; it does not reapply its action to today's state. A new request must satisfy today's version, capacity, policy and ownership checks. Read current status separately when the question is “what is true now?”

### Full provider path with a lost response

Suppose merchant A creates a payment for INR 100, authorizes it through the sandbox provider and submits a capture. The application freezes the capture intent, commits READY, claims a lease and commits IN_FLIGHT. The sandbox writes its approved capture decision before its response is lost. The application records UNKNOWN. At this moment our payment remains AUTHORIZED and no local capture journal is invented.

A verified provider callback for that existing command can commit its receipt and the confirmed capture effect together. Alternatively the merchant retries the same command and remote operation key, retrieving the original provider decision. Whichever completion wins, terminal-result checks and the common application transaction prevent a second local capture. A late matching result becomes recorded evidence rather than a rewind of the payment.

The committed payment event also enrolls a notification for an active merchant subscription. Its dispatcher may send the same delivery twice if the receiver commits but the 2xx response disappears. The receiver deduplicates the stable delivery ID and bytes. This duplicate notification is unrelated to the provider command identity and cannot recapture the payment. Kafka may likewise redeliver the event, where consumer receipts protect projection application.

### Separate local amount path

For partial operations, create another payment and authorize it manually. Before its first capture, submit a local CAPTURE of 60 with a fee of 3, then a CAPTURE of 40 with a fee of 2. M20 permanently binds this payment to amount mode. It cannot later adopt the provider workflow, and a provider-bound payment cannot enter this local mode.

The local journals now show gross capture 100, fee revenue 5 and merchant payable 95. Refund commands name their individual capture operations. Returning all principal leaves clearing zero and payable minus 5 because capture fees are retained. The system exposes that debt; it does not collect it or settle with a bank. Chapter 14 explains the exact policy and transaction guards.

### Derived view lifecycle alongside both paths

Both workflows generate payment lifecycle snapshots for internal consumers. Operators can build an isolated replacement view, validate it, roll reads back to the baseline, and replace the maintained target under the routing fence. Retiring the old target freezes its identity and retains evidence. They may inventory it, plan a bounded copy, preserve and independently verify its bodies, and approve or revoke review of that copy.

Those actions affect operational evidence and read routing. They do not reverse a payment journal or settle a provider obligation. This is how the two tracks connect while preserving separate authority: new financial activity feeds the event system, and the operational lifecycle keeps that system inspectable without silently rewriting financial facts.

## 5 Milestone 11 Target replacement and retirement

M10 established checked promotion and rollback. This chapter extends that one maintained shadow into a repeatable lifecycle with permanent target identities.

### 5.1 The problem this milestone solves

Milestone 9 can build several isolated views. Milestone 10 can enroll one of them, maintain it beside the baseline, and switch readers between those two views. Its lifetime restriction leaves a practical question: how can another reviewed build take over, or how can an unused warm target stop receiving updates?

Milestone 11 changes the restriction to **one maintained shadow at a time**. Replacement atomically enrolls a fresh candidate, switches reads to it and retires the old shadow. Explicit retirement removes the warm shadow while baseline reads continue. Both operations require the baseline to be active beforehand, fresh agreement and no tracked open gaps.

This is a local PostgreSQL read-model lifecycle. It does not move funds, replay financial operations, certify missing historical events, repair a damaged baseline, or deploy a new service.

### 5.2 Three lifecycle states and separate kinds of ownership

| State | Meaning in this project | Allowed next work |
| --- | --- | --- |
| `ISOLATED` | Run has never been enrolled; its own manifest/checkpoint workflow owns its target. | Step, catch up, validate, then checked enrollment. |
| `MAINTAINED` | Current route's shadow; normal consumption maintains it alongside baseline even while baseline serves reads. | Promote/rollback; replace or retire only after baseline rollback. |
| `RETIRED` | Run was enrolled previously and no longer owns live maintenance. | Inspect retained state and replay accepted historical commands; no new enrollment or rebuild mutation. |

`projection_rebuild_runs.routing_enrolled` now means **ever enrolled**. It never resets. `projection_routes.shadow_run_id` identifies the currently maintained shadow and can become null after retirement. `active_consumer` independently selects which view serves reads. Route revisions advance across every decision, including periods with no warm shadow.

`ProjectionRoutingService.target` exposes these states through authenticated `GET /api/v1/operations/projection-routing/targets/{runId}`. Its fresh read-only Repeatable Read transaction observes run metadata and current maintenance ownership together. `ProjectionRebuildService.validate` returns `RETIRED_EVIDENCE_RETAINED` for retired targets; comparison against their historical manifests can still report source drift.

**Why separate these identities?** A frozen build generation is historical evidence. A maintenance pointer names the target receiving future events. A read pointer names the target answering readers. None of those alone establishes current readiness.

### 5.3 Replacement is a fresh decision with explicit preconditions

`ProjectionReplacementCommand` contains request UUID, expected previous run UUID, new run UUID, expected build generation, expected route revision and reason. `ProjectionRoutingController.replace` obtains the actor from the authenticated principal. Caller-supplied actor text cannot establish authority.

For a new request, `ProjectionRoutingService.replace`:

1. Validates identity, counters, actor and single-line reason.
2. Starts its own `REQUIRES_NEW` / Read Committed transaction with a 30-second timeout.
3. Acquires the existing nonwaiting exclusive advisory fence and `SHARE ... NOWAIT` table freeze.
4. Requires baseline reads, the exact current revision and previous target, and a different new target in the configured source/topic.
5. Requires an isolated `BUILT` candidate with no pending/blocked inputs, manifest/shadow disagreement, unplanned rows or source additions/removals/changes.
6. Checks old shadow, baseline and candidate against current retained source and checks current view/envelope equivalence and open recovery gaps across source scopes.
7. Locks and marks the new run ever-enrolled, inserts immutable `REPLACE` audit with `previous_run_id`, and moves the maintenance/read pointers in the same commit.

```text
POST /api/v1/operations/projection-routing/replace
→ ProjectionRoutingController.replace
→ ProjectionRoutingService.replace
→ ProjectionRoutingRepository.fence
→ candidate + safe old target + safe new target
→ ProjectionRoutingRepository.switchRoute
→ enrollment + immutable audit + route COMMIT
→ next PaymentEventConsumer.consume visits baseline and new shadow
```

The old target is now retired. Normal delivery no longer visits it. Source writes remain asynchronous from projection updates, so replacement does not promise that every later payment write is immediately visible.

**Failure example:** candidate B validates at source version 0; authorization commits version 1. A replacement using the earlier review is refused. Consume version 1 into the existing maintained pair, catch B up using a new manifest generation, finish its steps and submit a fresh command with the observed generation/revision. Changing counters under an already accepted request UUID is not that new command.

Source-less baseline records remain important: the source-backed candidate cannot silently omit them. The equivalence check refuses that replacement. Arbitrary copying/correction and a source-less import workflow remain unimplemented.

### 5.4 Retirement releases maintenance while retaining evidence

`ProjectionRetirementCommand` carries request UUID, target run UUID, expected revision and reason. `ProjectionRoutingService.retire` uses the same authentication, fresh transaction, fence and data/gap checks.

Retirement requires baseline reads and the exact currently maintained run. It commits a `RETIRE` audit and advances the route revision while setting `shadow_run_id` to null. `PaymentEventConsumerRepository.maintainedShadow` then returns empty, so normal consumption applies only to baseline. Projection GET continues using its single SQL route/data lookup.

**Retirement retains** run identity, the irreversible enrollment flag, all manifest generations/items, accepted request decisions, receipts and the final projection rows. Database triggers continue freezing ever-enrolled build metadata after retirement. A fresh run can later be enrolled through `/promote` at the current revision; the retired identity cannot return.

This policy deliberately makes retirement different from deletion. Retirement itself adds no retention duration, physical purge, isolated-build cancellation or automatic cleanup job. Chapter 6 later adds a review window, which remains separate from deletion. Storage still grows and needs a separate, reviewed retention policy.

**Failure example:** retire A at revision 3; later enroll B at revision 4. An exact retry of A's retirement returns its original revision/time and `replayed=true`, without retiring B. An exact retry of A's original promotion also returns its historical result without making A active again. Read current status separately.

### 5.5 Atomic handover and evidence protection

| Concept | Where used | Practical effect and remaining cost |
| --- | --- | --- |
| Shared/exclusive transaction advisory fence | Consumer/rebuild shared gate; lifecycle exclusive gate | Maintenance cannot move midway through a consumer transaction; contention produces a bounded refusal. |
| Table freeze with `NOWAIT` | `ProjectionRoutingRepository.fence` | Comparisons and pointer writes share a protected fresh window; broad locks need an operational window. |
| `REQUIRES_NEW` and Read Committed | `replace` and `retire` | An older caller snapshot cannot authorize stale retirement/replacement; accepted decisions commit independently of the caller. |
| Revision and target compare-and-set | Command checks and route update | Another operator's decision invalidates stale intent; clients must read state before new commands. |
| Request idempotency | `projection_route_audit` lookup before current preconditions | Lost-response retry returns the original accepted result; replay still takes the fence and can be temporarily busy. |
| Deferred constraint triggers | V11 route/audit/enrollment checks | Pointer, transition history and frozen enrollment must agree at commit, including direct SQL attempts. |
| Permanent enrollment and immutable history | Ever-enrolled run guard; audit immutability | Retired targets remain frozen and cannot be re-enrolled; metadata and receipts remain stored. |
| Atomic consumer fanout | `PaymentEventConsumer.consume` | Baseline and the single current shadow still commit together, including duplicates; retirement does not reset receipt identity. |

V11 preserves V1–V10 migration files. It adds `previous_run_id` to decisions, backfills it for existing V10 revisions after the first, permits the two new actions, and permits an empty maintenance pointer. Existing request IDs, actors, timestamps, revisions and results retain their meaning. Upgrade coverage constructs real V10 promotion/rollback history, migrates it and verifies historical replay plus retirement.

Application and schema compatibility still need care: older Milestone 10 binaries do not implement the empty maintenance pointer or new actions. Deploy the Milestone 11 application before invoking this lifecycle. Mixed-version operation and downgrade are not established by the upgrade test.

Database consistency checks enforce the transition chain: first promotion; rollback from active shadow; replacement from baseline with a different target; retirement from baseline; and enrollment of a never-enrolled candidate after retirement. Reusing a historical target identity as a new enrollment is rejected.

### 5.6 Why these choices fit and what alternatives would change

| Chosen approach | Alternative considered | Why it fits this project and its cost |
| --- | --- | --- |
| Explicit baseline rollback before replacement or retirement | Replace the actively served shadow directly | Makes the preserved baseline the reviewed handover point and keeps active retirement invalid; adds an operator step. |
| Atomic checked replacement | Temporarily enroll both old and new shadows | Keeps normal fanout bounded to baseline plus one shadow and reuses the existing fence; candidate must be ready before handover. |
| Permanent retired identity | Reset enrollment and rebuild the same target | Keeps prior receipts, manifests and decisions interpretable; requires a fresh run and storage for each replacement. |
| Retain target artifacts | Delete receipts/projections during retirement | Separates maintenance release from irreversible evidence loss and accidental deduplication reset; does not reclaim disk. |
| Fresh fenced checks | Trust the last validation response or just an expected revision | A revision protects route intent, not changes to source data or gaps; global comparisons and locks have cost. |
| Same authenticated HTTP boundary | Add local CLI switches or a second authentication scheme | Keeps lifecycle decisions bound to the principal, operator role and CSRF session; multi-user/federated identity remains separate work. |
| Exact accepted-request replay | Blindly rerun the action against today's target | A retry cannot retire a different current target or reactivate an old one; clients must preserve the original body and actor. |

These alternatives are context-dependent, not universally worse. A larger deployment might need an additional catch-up subscription, multi-target orchestration or a different fencing mechanism. Those require their own ownership, consistency and failure design.

## 6 Milestone 12 Retained evidence and operating limits

After retirement, we need to inspect what remains and bound new routing comparisons. Inventory remains review-only; later chapters add plans and complete copies.

### 6.1 Why another milestone was needed

Milestones 9–11 let us build, promote, replace and retire read models without replaying financial operations. Retirement deliberately preserves receipts, projections, frozen manifests and immutable decisions. Repeated replacement therefore creates retained data that needs explanation and review.

The earlier routing fence also compares current source and target views while briefly excluding writes. Correct comparisons can become operationally expensive as those views grow. We need to see what is retained, understand which commands are being accepted or refused, and refuse an oversized new comparison before running its larger queries.

Milestone 12 adds two authenticated GET endpoints and a configurable comparison guard. It **does not delete evidence or grant permission to delete it**. It introduces review information and process-local measurements, not a production monitoring platform or a proven throughput limit.

### 6.2 Retention review is a separate decision from deletion

`ProjectionEvidenceService.review` reports rebuild targets in the configured source namespace and topic. Each target includes its run identity, current generation, lifecycle, creation time, retirement decision/time, artifact counts and review state. Neither payloads nor merchant references, operator labels or free-text reasons are exported.

The configured `reviewAfterDays` defaults to 30. This is a project review window, not an automatic expiry or a financial/legal retention rule. It starts at **retirement**, not at run creation. A long-lived target retired yesterday should not immediately be treated as old retired evidence.

| Review state | Meaning in this implementation |
| --- | --- |
| `RETAIN_MAINTAINED` | The target still receives live updates, including when baseline serves reads. |
| `RETAIN_ISOLATED` | The target has never been enrolled; this workflow does not cancel isolated builds. |
| `RETAIN_MISSING_RETIREMENT_EVIDENCE` | A retired lifecycle lacks its departing decision/time. |
| `RETAIN_UNCERTAIN_RETIREMENT_TIME` | Retirement appears before creation or after the database observation time. |
| `RETAIN_REVIEW_WINDOW` | A valid retirement has not yet reached the configured review age. |
| `REVIEW_RETIRED_EVIDENCE` | The age threshold has been reached; evidence can be considered in a separate review. |

Every target has `deletionPermitted=false`. The report's assessment is `REVIEW_ONLY_NO_DELETION_AUTHORITY`. Crossing the age boundary does not weaken immutability triggers, remove receipts, reset deduplication, authorize SQL deletion or certify complete event history.

Preservation requirements explicitly include run identities/routing decisions, frozen manifests/checkpoints, target receipts/projections, and baseline/source/recovery evidence. A future purge must define which facts survive and how historical accepted-request replay continues to work.

**Example:** A was replaced by B at revision 3; B was retired at revision 5. A's retirement is the revision-3 `REPLACE` that names A through `previous_run_id`. B's retirement is its own revision-5 `RETIRE`. Using the latest global audit timestamp for both would misstate A's age. `ProjectionEvidenceRepository.page` looks up each departing decision and orders by revision, rather than assuming timestamps establish logical order.

### 6.3 One report needs one observation snapshot

`ProjectionEvidenceService.review` owns a read-only `REQUIRES_NEW` transaction at Repeatable Read, with a 30-second transaction timeout. The database observation time, route revision, target lifecycle and inventory queries belong to that one committed snapshot. A caller's older or weaker transaction is suspended.

The report takes no routing advisory fence and does not freeze the application tables. A retirement can commit while the report is running; that report continues to describe the earlier consistent state. A later invocation observes the new state. The database test explicitly verifies Repeatable Read and transaction read-only mode while a concurrent retirement completes.

```text
GET /api/v1/operations/projection-routing/evidence?limit=50
→ ProjectionEvidenceController.review
→ ProjectionEvidenceService.review
→ ProjectionEvidenceRepository.observationTime / page / inventory
→ one read-only PostgreSQL snapshot
→ ProjectionEvidenceReport
```

UUID keyset pagination accepts `afterRunId` and a limit from 1 to 100, default 50. The repository reads one extra target to establish `hasMore`; the service inventories only the returned page. A continuation cursor is returned only when another row exists.

Each page is a separate transaction and observation. UUID order is not creation or commit order. New runs sorting before an already-used cursor can be missed in a multi-call traversal, and lifecycle state can change between calls. This is an investigation interface, not a frozen multi-page export. Bounded output also does not imply constant database work: each target's artifact aggregates can inspect its retained history.

### 6.4 What the inventory measures

For each selected target, `ProjectionEvidenceRepository.inventory` counts all retained generations, manifest items, consumer receipts, projection rows and routing decisions involving that run. A replacement contributes a decision to both the arriving and departing target's inventory, while a decision referring to the same run twice is counted once.

`retainedTextBytes` sums the database-encoded text lengths of:

1. The target consumer's stored receipt payloads.
2. Every retained manifest item's original payload.
3. Every retained manifest item's canonical payload.

It measures these retained text copies, not unique logical events. PostgreSQL `NUMERIC` aggregation and Java `BigInteger` avoid narrowing the sum to a small integer. The UTF-8 integration case uses currency/emoji characters to verify that bytes and Java string length differ.

This value excludes row headers, indexes, source-outbox storage, source snapshots in JSONB, projection fields, audit text, TOAST allocation and free-space behavior. It is **not table size, free disk or reclaimable storage**. A shared relation-size query would answer a different question and cannot directly attribute physical disk use to one run.

V12 adds a partial index for `REPLACE` decisions by departing target/revision and a source/topic/run index for target pages. It changes no evidence rows or lifecycle rules. V1–V11 migrations remain unchanged.

### 6.5 A comparison budget protects new routing work

`ProjectionOperationsProperties.maxComparisonRows` defaults to 100000, with validated bounds of 1 to 1000000. It is a **per-dataset row budget**, not a total across all datasets.

After acquiring the existing exclusive routing fence and table freeze, each new promote, rollback, replacement or retirement calls `ProjectionRoutingRepository.requireComparisonBudget`. It checks source payments, baseline projection rows, selected target projection rows and selected targets' current-generation manifest items. It counts at most budget + 1 visible rows in each check, then refuses if that dataset exceeds the budget.

Only after the guard passes do the larger manifest/equivalence/source/gap comparisons run. The fence prevents concurrent application writes from changing these datasets between the guard and decision. A refusal returns HTTP 409 with `PROJECTION_COMPARISON_BUDGET_EXCEEDED` and commits no enrollment, route change or audit.

An exact accepted-request replay is recognized before this new-work check. If a promotion was accepted when the data was smaller, retrying that same accepted command later still returns its historical result. It does not authorize a fresh comparison or repeat the promotion. The replay still needs the existing fence and can be temporarily busy.

**Example:** a one-row budget permits a one-payment promotion. A second payment is later created and consumed. Retrying the original accepted promotion returns its original result; a new rollback under that one-row budget is refused. After inspecting capacity and configuring an appropriate larger budget, a new checked decision can proceed. Increasing the budget does not waive any consistency or gap check.

The guard does not cap historical receipt volume, gap-query cost, tuple visibility work, I/O, bytes, all query-plan behavior or total response latency. The 30-second transaction timeout is not a complete HTTP wall-clock SLA. Production measurements remain necessary before choosing an operating budget.

### 6.6 Measure the operation through transaction completion

`ProjectionRoutingController` wraps the proxied routing service with `ProjectionRoutingMetrics.measure`. The service proxy finishes its transaction before returning. Measurements therefore include deferred commit checks; a method returning internally is not prematurely counted as an accepted decision.

Four fixed action labels are used: `PROMOTE`, `ROLLBACK`, `REPLACE`, `RETIRE`. For each action the snapshot exposes attempts, in-flight calls, accepted decisions, accepted replays, ordinary conflicts, budget refusals, other failures, total elapsed nanoseconds and maximum elapsed nanoseconds.

`System.nanoTime` measures elapsed time without relying on wall-clock adjustments. Short synchronized updates and immutable snapshots keep counters coherent under concurrent calls. The counters have no run IDs, actors, reasons or payload values as labels, so memory use does not grow with distinct requests. Totals saturate rather than wrapping on arithmetic overflow.

**Commit failure example:** a deferred database constraint rejects a promotion after its service body returns. The outer controller measurement records a failure, not an accepted decision. The transaction rolls back enrollment, route and audit together. The native PostgreSQL test injects this exact commit failure and verifies both outcomes.

`GET /api/v1/operations/projection-routing/operating-limits` exposes configuration and these measurements. Their scope is `THIS_PROCESS_HTTP_ROUTING_COMMANDS`:

- Authentication, authorization, CSRF and request-binding failures happen before measurement.
- Direct service calls, CLI commands and arbitrary SQL are outside this metric scope.
- Request parsing, response serialization and network transit are not included in operation timing.
- Counters reset on process restart; `since` identifies the measurement instance's start time.
- They are not durable audit history, cluster-wide totals, latency percentiles, alerts or a Prometheus export.

Accepted routing decisions remain durably recorded in PostgreSQL's immutable audit. Those records and process-local diagnostics have different jobs.

### 6.7 Alternatives and the tradeoffs retained

| Chosen approach | Alternative | Why it fits here and what it costs |
| --- | --- | --- |
| Review-only age classification | Delete automatically after N days | Preserves evidence until purge dependencies and approvals are defined; storage is not reclaimed. |
| Retirement age plus departing revision | Age from run creation or the latest global decision | Describes the target's own lifecycle and avoids treating a newly retired old build as expired; timestamps still need uncertainty handling. |
| One read-only Repeatable Read observation | Separate Read Committed queries | Keeps route/lifecycle/counts consistent while writers continue; maintains a snapshot and connection for the report. |
| UUID keyset pages | OFFSET or an unlimited export | Bounds returned targets and avoids walking earlier offsets; does not freeze a multi-call traversal. |
| Logical text-copy bytes | Shared-table relation size or string character counts | Gives a per-target logical inventory and handles multibyte text; physical capacity needs separate measurements. |
| Budget + 1 row probes under the fence | Full counts or only a timeout | Refuses oversized new comparisons early and makes the threshold inspectable; it does not bound every query cost. |
| Controller measurement around the service proxy | Count success inside the transactional service body | Includes actual commit failure; covers only controller-invoked routing commands. |
| Four fixed labels and local counters | Per-request tags or a complete monitoring stack | Keeps this authenticated diagnostic block small and bounded; cross-process aggregation, histograms and export remain future work. |

Micrometer/Actuator with an external monitoring backend is a useful future option for standardized export and cluster dashboards. It was not added merely to expose these four local operation summaries. Its wider production capabilities would require endpoint-security, persistence/aggregation and deployment decisions; it is not inherently a worse technology.

## 7 Milestone 13 Immutable bounded purge planning

Inventory describes current evidence. A plan adds immutable intent for one named target and exact artifact fingerprints, while still authorizing no deletion.

### 7.1 Why this milestone matters

The platform can repeatedly build, promote, replace and retire projection targets. Every retired target retains copied events, projections and build evidence. Milestone 12 made those retained copies visible and classified their age. An inventory screen alone cannot record a specific operator's intended cleanup scope or establish whether the same evidence still exists when someone returns later.

Milestone 13 records that intent as an immutable plan. It freezes one explicitly named retired target, its retirement decision, the route revision, the review policy, attributed reason and a bounded list of evidence identities and fingerprints. A fresh review compares this saved inventory with a new database snapshot.

This is preparation for a later retention workflow. **Every plan and review returns `deletionPermitted=false`.** No row is deleted, no immutability trigger is relaxed, no cleanup job runs, and no archive/export or approval is claimed. Even `CURRENT_REVIEW_ONLY` means only that the implemented freshness checks passed at that observation.

### 7.2 A plan names one target explicitly

`ProjectionPurgePlanCommand` contains `requestId`, `runId`, `expectedRevision` and `reason`. The request UUID also identifies the saved plan. The actor comes from `Principal.getName()` in `ProjectionPurgePlanController.create`; a caller-supplied `actor` cannot replace it.

`ProjectionPurgePlanService.create` validates bounded single-line attribution, acquires the existing routing fence, checks accepted-request replay and then verifies current scope, exact route revision and retirement age. The source namespace and topic come from server configuration.

Only a target classified as `REVIEW_RETIRED_EVIDENCE` by the existing `ProjectionEvidenceService.reviewState` can receive a new plan. Isolated builds, currently maintained targets, missing/uncertain retirement evidence and targets inside the review window are refused. Baseline reads alone do not mean its shadow is retired: a rolled-back shadow is still maintained.

**Example:** A target retired at revision 3 is still inside the default 30-day review window tomorrow. Planning returns 409 and saves nothing. After the window, a new request with the current route revision can record its inventory. This threshold is the project's review policy, not a legal retention rule.

**Alternatives:** accepting any old run would confuse creation age with retirement age; accepting a rolled-back shadow would include a live target. An automatic scan of all old targets would make the operator's exact intended scope less explicit. A future multi-target workflow could be useful, but this implementation keeps a single target and an absolute evidence-row cap.

### 7.3 Bounded inventory means artifacts, not just targets

One target can contain many generations and events. Limiting a request to one target would not alone bound its saved inventory. `ProjectionPurgePlanRepository.artifacts` admits at most **1,000 combined records** across:

- The rebuild run identity.
- Every retained generation.
- Every retained manifest item.
- Every receipt belonging to the target's generated logical consumer.
- Every projection belonging to that consumer.
- Routing decisions naming the target, including a replacement decision that names it as the departing target.

The cap includes retained identity/audit records; these are preservation dependencies, not a promise that they can later be removed. Each indexed query branch and the combined query use `LIMIT 1001`. An extra record proves the target exceeds the budget. The service refuses the entire plan, with no truncated or partial inventory. A target exactly at 1,000 records is accepted.

Returned records are sorted deterministically by artifact kind and identity before saving or comparing. Manifest identities include generation and payment UUID; receipt identities use event UUID; routing identities use revision. The containing plan supplies the run/consumer scope.

**Alternatives:** `count(*)` followed by a full export can do unnecessary work and needs a consistent boundary between count and export. A partial first page could conceal later evidence. Unbounded plans would grow unpredictably. A later resumable manifest format could support larger targets, but needs explicit completeness and checkpoint rules rather than silently increasing the scope here.

This is a row budget, **not a strict byte, disk, CPU or latency bound**. PostgreSQL must inspect visible tuples and serialize/hash each admitted row, which can contain large text/JSON values. A 30-second Spring transaction timeout constrains JDBC work; it is not a guarantee of HTTP wall-clock latency or production capacity.

The budget applies to each plan. It does not cap the number of plans, total retained audit storage or repeated authorized requests with new UUIDs. Global storage quotas, rate controls and a retention policy for planning history remain separate work.

### 7.4 Fingerprints detect same-count changes

For each selected row, PostgreSQL converts `to_jsonb(row)::text` to UTF-8 and computes SHA-256. `SET LOCAL TIME ZONE 'UTC'` normalizes timestamp rendering for each inventory query. The plan stores only kind, scoped identity and hash. Payment payloads, merchant fields and source snapshots are not exported as artifact bodies. The plan does expose its own operator and reason for audit purposes.

**Example:** an external SQL writer changes a retired projection's merchant reference without changing its primary key or the number of rows. Counts still match, but its fingerprint changes. Fresh review reports `ARTIFACTS_CHANGED` and `STALE_REPLAN_REQUIRED`.

**Alternatives:** counts alone miss content changes. Storing all original rows in the plan would duplicate sensitive payloads and increase storage/response size. Hashing only primary keys would miss changes under the same identity. SHA-256 is available in the supported PostgreSQL versions without adding an extension or external service.

These hashes are comparison evidence, **not digital signatures, an archive, an authenticity certificate or a portable canonical event format**. They depend on this schema and PostgreSQL JSON serialization; schema or representation changes may conservatively stale a plan. The original evidence is still required for recovery and any future verified preservation process. A database owner who can bypass guards remains outside this application-level protection.

### 7.5 Immutable storage and transaction ownership

V13 adds `projection_purge_plans`. V1–V12 remain unchanged. The table stores version 1 planning metadata, the bounded artifact JSONB list and frozen preservation obligations. Foreign keys retain references to the selected generation and retirement audit revision.

`protect_projection_purge_plan` rejects updates and deletes. On insert it checks the enrolled run's scope/current generation, its actual departing RETIRE/REPLACE decision and timestamp, absence from current maintenance, current route revision and review-age constraint. Service validation also checks full current inventory and uses the shared age classification. These layers address different entry points; database constraints do not authenticate an HTTP principal or independently prove that every saved hash is genuine.

Creation owns a new **Read Committed** transaction and uses the existing exclusive, nonwaiting routing advisory fence and SHARE NOWAIT table freezes. Participating routing/consumer operations cannot change the evidence between checks and insertion. Busy work returns a conflict rather than waiting indefinitely. Transaction rollback removes an unsuccessful insert; the other application tables are untouched by successful planning too.

**Alternatives:** reusing a caller's old snapshot could accept stale intent. An unlocked read followed by an insert could race routing or target updates. Blocking locks could create long queues behind maintenance. The reused fence is coarse and briefly excludes unrelated application writes; its simplicity is acceptable for this bounded learning workflow. A finer design would need its own proven lock order and writer participation protocol.

The existing database ownership/privilege assumptions still apply. This is not protection against a superuser, disabled triggers, table truncation or arbitrary uncoordinated administrative SQL.

### 7.6 Exact retries recover the original accepted plan

After acquiring the fence, creation looks up `requestId` before checking today's route revision, policy window or artifact inventory. The same UUID must carry the same run, expected revision, actor, reason and configured scope. Exact replay returns the original saved plan with `replayed=true`. A changed intent under the same UUID returns 409.

**Example:** a plan was accepted at route revision 3, but the response was lost. Another target is then promoted at revision 4. Repeating the original request returns its revision-3 plan; it does not create another plan or make that old plan current. Calling `/review` reports `ROUTE_REVISION_CHANGED`.

The create response is reloaded from PostgreSQL after insertion so timestamp precision matches later retrieval/replay. It is returned only after the service proxy completes transaction commit.

**Alternatives:** rejecting every old expected revision before recognizing retries would make a lost response ambiguous. Generating a fresh plan UUID on every retry would duplicate audit intent. Returning an existing plan for any command with the same UUID could silently approve a changed reason, target or operator.

### 7.7 Fresh review is a consistent observation

`ProjectionPurgePlanService.review` owns a read-only, new **Repeatable Read** transaction. Saved plan, database observation time, current route, scoped target and fingerprints belong to one snapshot. It does not acquire the routing fence or mutate a plan. A concurrent external drift can commit while the review continues describing its earlier snapshot; the next review sees the change.

| Blocker | Meaning |
| --- | --- |
| `ROUTE_REVISION_CHANGED` | A different route revision invalidates the plan's recorded operating context. |
| `REVIEW_POLICY_CHANGED` | The configured window differs from the frozen policy. |
| `TARGET_MISSING` | The scoped target cannot be found. |
| `TARGET_NOT_REVIEWABLE` | The current lifecycle/retirement-age checks no longer admit it. |
| `RETIREMENT_EVIDENCE_CHANGED` | The generation or departing revision/time differs. |
| `ARTIFACTS_CHANGED` | A bounded row identity or content fingerprint differs. |
| `ARTIFACT_BUDGET_EXCEEDED` | Today's target exceeds the inventory cap. |

No blockers gives `CURRENT_REVIEW_ONLY`; otherwise the assessment is `STALE_REPLAN_REQUIRED`. Both return `deletionPermitted=false`. Stale plans remain immutable; a new plan uses a new request UUID and fresh expected revision.

This review checks the frozen target inventory and its recorded context. It does not rerun source/baseline reconciliation, certify historical coverage, assess every current recovery gap or verify preservation outside PostgreSQL. Growth of unrelated source payments alone does not change a retired target's inventory. Chapters 8 and 9 add approval and lifecycle checks. A future execution protocol still needs its own additional safety checks.

**Alternatives:** separate Read Committed queries could mix old metadata with new artifacts. Holding exclusive locks throughout a human review would obstruct application traffic. Treating a successful GET as approval would confuse an observation with authority. Any future executor must obtain its own fresh execution fence and conditions; a review response can become stale immediately after it returns.

### 7.8 Preservation obligations are explicit

The saved version-1 obligations require keeping run identities/routing decisions, preserving frozen manifests/checkpoints and target receipts/projections before any future purge, and keeping baseline/source/ledger/recovery evidence outside scope. Preserving routing history also protects exact historical request replay and permanent non-reenrollment of retired runs.

The plan by itself supplies no archive, preservation acknowledgement, approval, execution token, deletion batch, scheduler or storage reclamation. Chapters 8 and 9 add a verified copy and review approval; two-person approval and physical execution remain unavailable. A plan alone cannot discharge preservation obligations. The current target's original rows all remain in PostgreSQL, and the existing immutability protections continue to apply.

### 7.9 Actual request flow and important files

```text
POST /api/v1/operations/projection-routing/purge-plans
→ Spring Security operator role + CSRF
→ ProjectionPurgePlanController.create
→ ProjectionPurgePlanService.create
→ ProjectionRoutingRepository.fence
→ ProjectionEvidenceRepository.target + ProjectionEvidenceService.reviewState
→ ProjectionPurgePlanRepository.artifacts / insert / find
→ PostgreSQL V13 immutable planning evidence
→ ProjectionPurgePlan.Result
```

Read the saved intent with `GET .../purge-plans/{requestId}`. Recheck it with `GET .../purge-plans/{requestId}/review`. The existing four-action routing metrics remain scoped to promote/rollback/replace/retire; planning is not silently included in those counters.

| File | Purpose |
| --- | --- |
| `ProjectionPurgePlanCommand.java` | Validated UUID, exact integral revision and bounded reason. |
| `ProjectionPurgePlan.java` | Saved plan, artifact fingerprints, replay result and fresh review DTOs. |
| `ProjectionPurgePlanController.java` | Authenticated HTTP entry points and principal-derived actor. |
| `ProjectionPurgePlanService.java` | Fresh creation, idempotency, scope, policy and review checks. |
| `ProjectionPurgePlanRepository.java` | Capped inventory hashes and durable JSONB plan storage. |
| `ProjectionEvidenceRepository.java` | Shared exact scoped target lookup and departing evidence. |
| `V13__add_projection_purge_plans.sql` | Table, foreign keys, retirement checks and immutable audit guard. |
| `ProjectionPurgePlanDatabaseTests.java` | Real database boundaries, drift, retries, concurrency and upgrade checks. |

## 8 Milestone 14 Verified preservation and attributed approval

A digest cannot reconstruct a lost row. Preservation adds full named artifact bodies and one exact downloadable file; chapter 9 adds approval expiry, withdrawal and offline checking.

### 8.1 The problem after Milestone 13

Milestone 13 froze a bounded list of retired-target identities and fingerprints. That list can detect drift, but a hash cannot reconstruct an event payload, a manifest or a projection. Before considering future retention changes, we need a complete copy of the named evidence and a record of what an authenticated operator reviewed.

Milestone 14 saves a versioned preservation bundle containing the original plan and every named artifact body. It verifies each body against the plan, stores the exact issued JSON bytes and their whole-file SHA-256, provides an authenticated download, and records an explicit preservation-review approval tied to that copy.

**The copy is initially stored in the same PostgreSQL database.** It is a preservation/export artifact, not an independent backup. An operator can download it to separate storage, but the server cannot prove that an external copy exists, remains durable or has been restored successfully. Approval records an acknowledgement, not proof of those external facts.

Every metadata/approval/review response still reports `deletionPermitted=false`. There is no delete executor, relaxed evidence trigger, automatic retention job or restore/import API.

### 8.2 What a preservation bundle contains

`ProjectionPreservation.Bundle` has format version 1, preservation request UUID, the exact saved `ProjectionPurgePlan`, preservation observation time, authenticated actor, reason and a sorted record list. Each `ExportRecord` has artifact kind, scoped identity, SHA-256 and `body`.

The body is the PostgreSQL `to_jsonb(row)::text` representation used by the original inventory, rendered with UTC timestamp settings. It includes full row data. A receipt body therefore includes its retained payload text; a manifest includes source snapshots, original/canonical payloads and progress facts. Run, generation and related routing rows are included too. Baseline receipts/projections, source payments/outbox, ledger and recovery tables are outside this target's export scope. Canonical receipts remain canonical; the export does not recreate unretained Kafka wire bytes or missing historical events.

The body is stored as a JSON **string inside the bundle**, rather than reparsed and rewritten as an embedded object. That preserves the exact row text on which the plan's fingerprint was computed, including representations of numbers and timestamps. Original TEXT payload values survive as fields within that row representation.

**Example:** a receipt contains a merchant reference with `₹` and an emoji. Its UTF-8 byte count differs from its Java string length. The exported receipt's `payload` value must equal the retained database payload exactly; comparing character counts would not verify file length or integrity.

**Alternatives:** exporting only IDs/hashes cannot reconstruct evidence. Exporting selected generations would omit retained historical manifests. Rewriting bodies through an application DTO could omit columns or change the bytes being compared. A PostgreSQL dump could preserve a wider database state, but would not by itself express this plan's bounded scope and operator intent. The bundle is deliberately scoped evidence, not a database backup or a ready-to-run restore script.

### 8.3 Verification happens at two levels

`ProjectionPreservationVerifier` checks:

1. The saved file length and SHA-256 agree with its exact UTF-8 bytes.
2. Format version, preservation UUID, plan, actor, reason and timestamp match the saved metadata.
3. The record count equals the plan's artifact count.
4. Every body hashes to its declared row digest.
5. The ordered kinds, identities and row digests equal the original plan manifest exactly.

Missing, duplicate, substituted, reordered or altered records cannot satisfy the manifest comparison. Recomputing the whole-file hash after tampering does not make changed row bodies agree with the original plan.

The verifier runs before a preservation insert, before a new approval, and before serving a download. Approval review also verifies the stored copy. A metadata GET alone is not a fresh integrity check. New downloads and approvals fail if integrity verification fails; an existing approval review reports `PRESERVATION_INTEGRITY_FAILED`.

**Alternatives:** a whole-file checksum alone detects accidental corruption against a trusted expected digest, but cannot establish agreement with the earlier plan if both content and digest are replaced. Row checks alone do not protect bundle metadata or the exact downloadable file. Both checks are needed for the implemented contract.

SHA-256 provides comparison evidence, not a digital signature, independent provenance or certified preservation. These row hashes depend on the schema and PostgreSQL's JSON representation. Schema/representation changes may require a fresh plan or format version. A database administrator who bypasses triggers or replaces all trusted records remains outside the application's ownership assumptions.

### 8.4 Why the exact serialized file is stored as TEXT

V14 stores the generated `export_json` as TEXT, alongside byte length and SHA-256. Download returns those stored UTF-8 bytes directly through `ResponseEntity<byte[]>`. It does not deserialize and pretty-print the file on each request.

JSONB remains useful for the plan's queryable manifest. For a downloadable artifact with an advertised whole-file digest, reformatting JSON would change the bytes while potentially retaining the same logical content. Storing the issued representation avoids that ambiguity.

The response has `Content-Length`, `Content-Disposition: attachment`, `Cache-Control: no-store` and `X-Content-SHA256`. The filename is constructed from a UUID. This export contains sensitive artifact bodies and is protected by the operator boundary; it is not a public payment endpoint.

**Alternatives:** generating the export anew for every download would conflate preservation time with current database state. Storing only parsed JSONB and reserializing it would make the original issued byte sequence harder to guarantee. External object storage could provide a stronger independent preservation boundary, but requires credentials, storage policies, integrity verification and failure handling that this local learning milestone does not implement.

### 8.5 Bounded rows need bounded bytes too

The existing combined 1,000-artifact cap remains. `ProjectionPurgePlanRepository` shares one bounded evidence query between fingerprinting and full-row export, preventing the two paths from choosing different artifact classes.

Before transferring bodies to the application, `exportRecords` checks the sum of raw UTF-8 body lengths. More than 8 MiB is refused. After constructing the envelope, `encode` checks its actual serialized UTF-8 length against the same **8 MiB** limit. No partial copy is stored on refusal.

**Example:** a raw body containing many backslashes may fit under the raw-byte bound but expand when encoded as a string in the outer JSON document. It can exceed the final-file limit. The integration tests cover both raw overflow and escaped-serialization overflow.

The SQL checks the cap before sending full bodies, but PostgreSQL still performs row serialization to measure them. The application also allocates intermediate strings and bytes. These guards bound the admitted data, not peak process memory, all query cost, request concurrency or HTTP latency. Transactions retain a 30-second timeout. The number of saved copies/approvals and total storage are not globally capped; no rate limit or storage quota was added.

### 8.6 Creation owns a fresh fenced transaction

`ProjectionPreservationService.preserve` owns new Read Committed work, validates single-line attribution, acquires `ProjectionRoutingRepository.fence`, checks exact accepted-request replay and then evaluates the saved plan's current conditions.

The command's explicit expected revision must equal the plan's recorded revision. The existing plan review checks current route revision, review policy, target lifecycle/retirement evidence and artifact manifest. The copy is then collected and verified against that exact plan while the existing advisory/table fence excludes participating writers.

The shared transaction helpers in `ProjectionPurgePlanService` reuse the current transaction. Calling its public `REQUIRES_NEW` report methods from inside a preservation operation would create a second snapshot/connection rather than express that ownership directly. The public Milestone 13 endpoints retain their existing transaction contracts.

**Alternatives:** an unlocked report followed by a later export could combine a valid old plan with changed bodies. Reusing an old caller snapshot could miss recently committed drift. Blocking locks could queue maintenance indefinitely. The nonwaiting fence is coarse and can briefly exclude unrelated writes; preserving this established lock protocol is preferable here to introducing an unproven second one. A busy fence returns a conflict without a partial copy or approval.

### 8.7 Approval binds explicit human intent to one copy

`POST .../purge-plans/{planId}/approvals` accepts a request UUID, preservation UUID, expected revision, exact lowercase export SHA-256, `preservationAcknowledged: true` and a reason. The actor is always taken from the authenticated principal.

Acknowledgement must be a JSON boolean. Missing/null/false, numeric values and the string `"true"` are rejected. `StrictAcknowledgementDeserializer` prevents Jackson coercion from turning a differently typed input into approval. The existing strict integral revision deserializer is reused.

New approval takes the same fresh fence, rechecks the plan, confirms that the preservation belongs to that plan and digest, verifies the complete saved copy and inserts an immutable audit record. A composite foreign key also prevents an approval from being linked to a different plan/copy/digest combination.

Its assessment is `PRESERVATION_REVIEW_APPROVED_NO_DELETION_AUTHORITY`. The acknowledgement records that the operator accepted responsibility for preservation review. It does not establish that a download was saved off-site, that external storage is durable or that deletion is allowed. The same configured operator may create and approve; two-person separation and a general identity-management system were not introduced.

**Alternatives:** approving only a plan UUID would not say which copied bytes were reviewed. A checkbox without a digest could bind intent to the wrong file. Automatic approval after export would eliminate the deliberate operator decision. Enforcing separate people would require a broader identity/role policy than the current single-account operator boundary supports.

### 8.8 Immutable history and current validity are different

Both preservation and approval commands use exact request replay. UUID reuse must retain the original plan, revision, actor, reason and, for approval, copy/digest. A changed intent returns 409. Exact accepted retries are recognized before today's new-work freshness checks.

Preservation retry returns its original metadata and verifies the saved copy; a corrupted copy can therefore prevent that retry. Approval retry returns its original historical audit. It does not reapprove today's state or bypass a future executor's checks.

**Example:** preservation and approval were accepted at route revision 3. A new target is promoted at revision 4. Retrying either original command recovers its original result. New preservation/approval for the stale plan is refused. The old bundle remains downloadable if its integrity still verifies, and approval review reports `ROUTE_REVISION_CHANGED`.

`GET .../approvals/{requestId}/review` owns a new read-only Repeatable Read snapshot. It combines the existing plan blockers with stored-copy integrity/binding checks. No blockers gives `CURRENT_APPROVAL_REVIEW_ONLY`; otherwise it returns `STALE_APPROVAL_REPLAN_REQUIRED`. Both have `deletionPermitted=false`. Concurrent drift can commit while the report retains its earlier snapshot; the next report sees it.

Neither review certifies complete event history, reruns source/baseline reconciliation, evaluates all recovery gaps or proves external preservation. A future retention executor needs its own fresh safety protocol. Chapter 9 adds approval revocation, fixed expiry and independent preservation verification; none grants deletion authority.

### 8.9 Schema and security boundaries

V14 adds `projection_preservations` and `projection_preservation_approvals`; V1–V13 remain unchanged. Constraints enforce row/byte bounds, format/envelope identity, whole-file digest, plan/copy links and acknowledgement. Insert guards require the planned current route revision. Update/delete guards keep both records immutable.

The service performs the full manifest and freshness verification; SQL constraints alone do not authenticate the actor or prove every copied row belongs to the original inventory. Normal application writes use the shared fence. Privileged direct SQL, disabled triggers and schema administration remain outside that protocol.

All new endpoints sit under `/api/v1/operations/projection-routing`, reusing Basic/BCrypt configuration, `PROJECTION_OPERATOR`, POST CSRF/session protection and deny-all behavior when credentials are absent. GETs require the operator too. Saved-copy/approval reads validate the original plan's configured source/topic scope. No payload-bearing metadata is made available through the public payment API. Existing four-action routing metrics retain their original scope; new counters/exporters were not added.

### 8.10 Actual flow and important files

```text
POST /api/v1/operations/projection-routing/purge-plans/{planId}/preservations
→ ProjectionPreservationController.preserve
→ ProjectionPreservationService.preserve
→ ProjectionRoutingRepository.fence
→ ProjectionPurgePlanService.reviewInTransaction
→ ProjectionPurgePlanRepository.exportRecords
→ ProjectionPreservationVerifier.encode / verify
→ ProjectionPreservationRepository.insert
→ PostgreSQL
```

Download uses `ProjectionPreservationService.download` → `verify` → exact saved bytes. Approval uses `ProjectionPreservationService.approve` → fresh plan/copy checks → `ProjectionPreservationRepository.approve`.

| File | Purpose |
| --- | --- |
| `ProjectionPreservation.java` | Commands, versioned bundle, metadata, approval, review and download DTOs. |
| `ProjectionPreservationController.java` | Protected create, download, approval and review HTTP endpoints. |
| `ProjectionPreservationService.java` | Scope, freshness, exact retries and durable operator intent. |
| `ProjectionPreservationVerifier.java` | File/header/manifest/row integrity and final byte budget. |
| `ProjectionPreservationRepository.java` | Immutable copy and approval persistence. |
| `ProjectionPurgePlanRepository.java` | Shared capped inventory and raw-byte-checked body selection. |
| `StrictAcknowledgementDeserializer.java` | Explicit boolean acknowledgement without coercion. |
| `V14__add_projection_preservation_and_approval.sql` | Tables, constraints, cross-links and immutable guards. |

## 9 Milestone 15 Approval lifecycle and offline verification

A saved acceptance is historical. This chapter adds a fixed validity interval, explicit withdrawal and a verifier that runs before Spring startup.

### 9.1 The problem this milestone solves

Milestone 14 copied every artifact named by a bounded retired-target plan and recorded which authenticated operator acknowledged one exact copy. Two gaps remained: an accepted approval had no time limit or withdrawal mechanism, and checking a downloaded file required application-side verification or a manual whole-file checksum.

Milestone 15 gives new approvals a fixed lifetime, adds immutable revocations, and provides a packaged command that verifies a downloaded bundle against independently trusted metadata and its original plan. These are evidence controls. Every result still has `deletionPermitted=false`; physical deletion and restore/import remain unimplemented.

**Example:** an operator approved a copy at 10:00 with a 60-minute lifetime. At 10:30 they discover the external storage location is unsuitable and revoke that approval. The original decision remains retrievable, an exact retry returns that same decision, and a fresh review reports `APPROVAL_REVOKED`. At 11:00 the same review also reports `APPROVAL_EXPIRED`.

### 9.2 Historical acceptance and current validity

`ProjectionPreservation.Approval` remains an immutable decision. Its new `validityMinutes` and `expiresAt` fields describe the policy accepted with that decision. `ProjectionPreservationService.reviewApproval` separately observes whether it is current.

| Operation | Meaning |
| --- | --- |
| Approval metadata GET | Returns the accepted decision, including its original validity interval. |
| Exact approval POST retry | Recovers that same accepted decision; it does not renew it. |
| Fresh approval review GET | Checks the plan, copy, observed time and committed revocation in one snapshot. |
| New approval request UUID | Creates new intent after all current new-approval checks pass. |
| Revocation POST | Appends withdrawal of one particular approval. |

**Chosen approach:** retain history and compute current validity separately. Mutating an approval status or deleting its row would erase or rewrite what the operator originally accepted. Making a retry fail merely because time passed would make a lost HTTP response ambiguous. Exact replay preserves the existing idempotency contract; a caller needing current validity must request a fresh review.

**Cost:** an accepted response alone cannot be treated as present authority. Every future action that depends on approval must implement a fresh, correctly fenced decision of its own. There is no such executor in this release.

### 9.3 Snapshotting a bounded approval lifetime

`ProjectionApprovalProperties` binds `FINCORE_PROJECTION_APPROVAL_VALIDITY_MINUTES`. Its default is 60 minutes and the allowed range is 1–1440; invalid configuration fails validation during Spring startup.

For a new approval, `ProjectionPreservationService.approve` captures the PostgreSQL observation time once, normalizes it to PostgreSQL microsecond precision, checks the plan and copy, and stores:

```text
approvedAt = captured observation time
validityMinutes = configured duration at acceptance
expiresAt = approvedAt + validityMinutes
```

The repository writes all three values together. Changing configuration to five minutes tomorrow affects newly accepted approvals, not yesterday's stored interval or its replay.

Using the database clock keeps this decision on the existing evidence/retirement time base; JVM clocks could differ between application instances. The interval starts at the captured observation before checks, so processing time consumes part of it. Database time can still jump: the uncertainty blocker is conservative handling of an observed reversal, not proof that time is globally correct.

At review, time before `approvedAt` yields `APPROVAL_TIME_UNCERTAIN`; time equal to or after `expiresAt` yields `APPROVAL_EXPIRED`. Expiry is inclusive at the boundary. Other plan/copy blockers remain in effect even within the interval. There is no scheduler that updates rows when a clock reaches the deadline.

**Alternatives and tradeoffs:** calculating expiry from the current configuration on every read would retroactively change accepted policy. An operator-supplied expiry would widen the command contract and allow inconsistent intervals. An expiry-update job adds mutable state, scheduling and recovery concerns for a condition that can be derived. A fixed stored interval is simpler here, but depends on trustworthy database time and does not itself serialize a future consequential action.

### 9.4 Upgrading old approvals without inventing history

`V15__add_approval_validity_and_revocation.sql` adds nullable validity columns with no default or backfill. Existing V14 rows retain their prior audit fields and both new fields remain null. A fresh review reports `APPROVAL_VALIDITY_UNSPECIFIED` for them. An exact accepted retry still recovers the legacy decision, and it can be revoked.

For new inserts, a separate trigger requires explicit validity. A check constraint requires 1–1440 minutes, finite timestamps and the exact arithmetic relationship. Both partial-null combinations are rejected. Existing update/delete immutability guards remain in place; old migrations are unchanged.

**Why this choice:** assigning a new expiry to old approvals would claim a policy the operator never accepted. Rewriting V14 would break applied migration history. Making the new columns globally non-null would force a fabricated backfill. Nullable historical data plus a new-insert guard expresses the actual compatibility rule, at the cost of handling legacy approvals explicitly in reads and reviews.

This is additive schema compatibility, not a mixed-version deployment guarantee: an older binary cannot insert a new approval after V15 because it omits the required validity fields.

Approval JSON responses also gain `validityMinutes` and `expiresAt`; clients with closed response schemas need to accept them, including null values for legacy decisions. The existing approval request shape is unchanged.

### 9.5 Revocation is a new immutable fact

`ProjectionApprovalLifecycleController.revoke` receives only a request UUID and bounded single-line reason. The actor comes from the authenticated principal. Existing `PROJECTION_OPERATOR`, Basic/BCrypt and POST CSRF controls apply; absent operator credentials leave the boundary closed.

`ProjectionApprovalLifecycleService.revoke` owns a new Read Committed transaction with a 30-second timeout. It validates attribution, acquires a dedicated nonwaiting advisory transaction lock, checks the approval's configured source/topic scope, recognizes exact replay, rejects conflicting intent or another revocation, and inserts the audit.

`projection_approval_revocations` has a request UUID primary key, a unique approval UUID foreign key, database-recorded time, actor and reason. Update/delete triggers protect the record. There is one revocation per approval, no unrevoke, no automatic withdrawal of every approval for the same plan, and no deletion of its preserved copy.

**Example:** request R revokes approval A. Repeating R/A with the same actor and reason returns the original with `replayed=true`. Reusing R for another approval or reason conflicts. A new request attempting to revoke A again also conflicts; retrieve A's existing revocation instead.

**Alternatives:** a mutable `revoked` flag loses the original withdrawal attribution and request replay contract. Reusing approval rows for cancellation changes their meaning. Revoking an entire plan would silently affect separately accepted decisions. The separate table keeps the unit of withdrawal explicit and adds one scoped lookup during fresh review.

### 9.6 Why withdrawal uses its own lock

Granting approval requires current route/plan/copy checks under the existing routing fence. Withdrawal requires identity and scope, even if that evidence has since become stale or damaged. Revocation therefore uses advisory key `742190311`, separate from routing key `742190310`, and does not freeze routing tables or load the preservation body.

An expired approval or an approval whose stored copy no longer verifies can still be revoked. A held routing advisory fence does not itself prevent revocation. Concurrent application revocation requests are serialized by the dedicated nonwaiting lock; a busy request gets a conflict and can retry its exact intent. PostgreSQL uniqueness also prevents two revocation rows for one approval.

**Alternatives:** requiring a current route revision or verified copy to revoke would make withdrawal unavailable precisely when those conditions fail. Sharing the routing lock would create unnecessary coupling. Ordinary row locking or finer locks could increase concurrency, but would need an ordering protocol for both request identity and approval identity. The coarse dedicated lock favors straightforward correctness at low operator volume; it serializes revocations across the configured database and can refuse unrelated simultaneous withdrawals.

This is a logical separation, not a guarantee of zero waiting under every raw SQL/DDL lock. No lock coordinates a future deletion action yet.

### 9.7 One snapshot for a fresh review

`ProjectionPreservationService.reviewApproval` retains its new read-only Repeatable Read transaction. Its database observation, plan state, copy and revocation read share one snapshot. A revocation may commit while the report is being produced: that report can still describe the earlier snapshot, while the next review sees withdrawal.

The lifecycle blockers added here are:

- `APPROVAL_VALIDITY_UNSPECIFIED` — legacy approval has no accepted interval.
- `APPROVAL_TIME_UNCERTAIN` — observed time precedes acceptance.
- `APPROVAL_EXPIRED` — observed time reached the saved deadline.
- `APPROVAL_REVOKED` — a revocation is visible in this snapshot.

Existing route/policy/target/artifact and copy-binding/integrity blockers are combined with these. No blockers still means `CURRENT_APPROVAL_REVIEW_ONLY`, with `deletionPermitted=false`.

**Tradeoff:** a coherent observation is easier to interpret than separate reads that could mix times, but it is immediately historical. Repeatable Read is useful for reports; a future executor needs its own fresh concurrency protocol rather than trusting this earlier response.

### 9.8 Verifying a file without starting the service

`FincorePaymentsApplication.main` recognizes `verify-preservation` as the first argument before calling Spring. It dispatches to `PreservationFileVerifierCli.run`, which creates a strict JSON mapper and the shared `ProjectionPreservationVerifier` directly. No Spring context, database connection, migration, Kafka client or operator account is initialized.

```powershell
java -jar target/fincore-payments-0.0.1-SNAPSHOT.jar verify-preservation `
  --bundle=projection-preservation.json `
  --metadata=preservation-metadata.json `
  --plan=original-plan.json
```

The three files are different inputs: the downloaded exact bundle bytes, independently trusted preservation metadata, and the independently trusted original saved plan. Pass the metadata/plan objects returned by their GET endpoints, not the wrapper object from their POST responses. The runbook covers retaining those references.

Verification checks exact UTF-8 file length and whole-file SHA-256; version/envelope/metadata/plan agreement; every body digest; and exact ordered kind/identity/digest agreement with the original manifest. It reuses the M14 verifier so server and standalone checks share the integrity contract.

**Alternatives:** a shell checksum is useful but cannot alone compare row scope and metadata with the original plan. An HTTP verify endpoint would depend on the same application/database being available. A second independently rewritten verification algorithm could diverge. Reusing the packaged implementation keeps one contract, but means a trusted Java runtime and trusted verifier JAR are part of the checking environment.

### 9.9 Strict input handling and bounded resource use

The CLI reads at most 8 MiB plus one byte for the bundle and 1 MiB plus one byte for each reference, refusing overflow. It decodes UTF-8 with malformed/unmappable input rejected. Format v1 rejects duplicate keys, trailing JSON values, unknown fields, missing/null constructor fields, coerced scalar values, fractional/exponent integer encodings and numeric timestamps. Instants use ISO-8601 strings. Exactly three unique named path options are required.

**Example:** changing `artifactCount` from `1` to `"1"` or adding a second identical `formatVersion` key is refused even if a permissive JSON reader could interpret it. Appending a newline to the bundle changes the exact file digest. Metadata and plan formatting may vary within the strict JSON contract because their trusted values, rather than their own byte hashes, are compared.

**Why strict parsing:** a preservation check should not silently repair or reinterpret malformed evidence. Unknown-field rejection intentionally makes v1 closed; adding a field later needs a deliberate compatibility/version decision. The limits bound admitted file data, not every allocation or peak memory cost. This command accepts local paths, not storage URLs, and imposes no general storage sandbox.

On success, one JSON report returns `VERIFIED_OFFLINE_NO_DELETION_AUTHORITY`, plan/copy IDs, digest, byte count, artifact count, `databaseContacted=false` and `deletionPermitted=false`, exit code 0. Any handled refusal returns a generic JSON failure and exit code 2. File paths, bodies, actor/reason and exception traces are not printed. Sanitization protects sensitive evidence, while deliberately sacrificing detailed command-line diagnostics.

### 9.10 Integrity, authenticity, durability and restoration

These are different claims:

| Claim | What this milestone establishes |
| --- | --- |
| Integrity | These supplied bytes match these trusted metadata/plan values at this observation. |
| Authenticity | Trust must come from the independently retained references and trusted verifier; no signature or external attestation is added. |
| External durability | Not established by opening one matching local file. Storage lifecycle, redundancy and future readability are outside the checker. |
| Restore readiness | Not established. There is no import procedure or successful restore drill. |
| Current approval validity | Requires a fresh server review; the offline CLI does not read revocations, route state or current policy. |
| Permission to delete | Never granted. |

If an attacker replaces the bundle, metadata and plan together, a self-consistent set can pass. Extracting a plan from the same untrusted bundle and calling it independently trusted does not establish provenance. A saved successful console report is informational; the server neither receives it nor treats it as authenticated evidence.

Signatures, object storage, retention policies and restore drills are plausible later controls. They require key/credential management, trust rules, failure handling and independent observations. None is universally superior by itself; each addresses a different missing claim.

### 9.11 Actual request and command flows

```text
POST /api/v1/operations/projection-routing/approvals/{approvalId}/revoke
→ ProjectionApprovalLifecycleController.revoke
→ ProjectionApprovalLifecycleService.revoke
→ ProjectionApprovalLifecycleRepository.fence / request / revocation / revoke
→ PostgreSQL projection_approval_revocations
→ ProjectionApprovalLifecycle.Result
```

```text
java -jar ... verify-preservation --bundle=... --metadata=... --plan=...
→ FincorePaymentsApplication.main
→ PreservationFileVerifierCli.run
→ strict local file reads
→ ProjectionPreservationVerifier.verify
→ sanitized JSON report + process exit code
```

The revocation path also reads the approval and `ProjectionPurgePlanService.scoped` to enforce configured ownership. Payment, ledger, outbox, offsets and target bodies are unchanged.

### 9.12 Verification strategy and practical failures

The new tests cover HTTP principal/role/CSRF and validation, configuration bounds, expiry boundaries and policy snapshots, exact historical replay, immutable revocations, scope/conflicts/lock contention, concurrent withdrawals, late rollback, unchanged unrelated tables, coherent concurrent review and a real V14→V15 upgrade with legacy replay and new decisions.

Offline tests cover Unicode bytes, changed/truncated/newline files, wrong references, recomputed file digests with changed bodies, parser ambiguity, invalid encodings, size limits, option failures and sanitized output. Packaged process tests use invalid Spring configuration and unreachable infrastructure to prove early dispatch; altered bytes and missing arguments fail without starting Spring.

Consult the [summary](MILESTONE_15_SUMMARY.md) for the final one-run totals and environment limits. Earlier milestone totals remain historical; do not sum focused runs with the full run.

The M15 summary preserves that milestone's dated Kafka and packaged-verifier evidence. Chapter 18 reports the latest broader run and its narrower environment coverage.

## 10 Milestone 16 Merchant authentication and tenant isolation

The next track establishes merchant authority before introducing a remote provider. All current merchant-owned payment paths use this boundary, including chapters 11 through 14.

### 10.1 Why this milestone comes next

The platform already has payment transitions, retry-safe creation, balanced journals, outbox/Kafka delivery and extensive projection recovery/maintenance. Its HTTP payment endpoints were still public, idempotency keys shared one namespace, and currency balances aggregated every payment. Those are acceptable teaching foundations, but they cannot serve multiple independent merchants safely.

The updated roadmap returns to the original payment-platform capabilities. Milestone 16 establishes who the merchant is and which financial data that identity may access. The preservation/restore branch remains recorded as later operational work; no physical deletion is introduced.

**Example:** two shops both use `Idempotency-Key: order-123`. Previously they would collide in one global namespace. Now each shop can create its own payment. Repeating the same shop's original request returns its original response; changing its intent conflicts. Knowing the other shop's payment UUID provides no read or transition authority.

### 10.2 What is now protected

All existing `/api/v1/payments` routes, their ledger/event/projection subresources and `/api/v1/ledger/accounts/{currency}/balances` require merchant authentication. New `/api/v1/merchant/me` and `/csrf` endpoints expose safe identity and CSRF metadata.

| Surface | Authentication and scope |
| --- | --- |
| Payment create | Database-backed merchant credentials; ownership comes from the authenticated principal. |
| Payment read / authorize / capture / fail / refund | Same identity plus an authoritative owned-payment check. |
| Payment journals / outbox events / routed projection | Ownership checked before invoking the existing read service. |
| Currency balances | Totals from journals belonging to this merchant's payments only. |
| Projection operator API | Existing separate operator provider, role and CSRF boundary; remains privileged and cross-merchant. |
| Headless operations CLI / database administrator | Trusted operational access, outside merchant HTTP isolation. |

Unknown/unmatched routes are denied by the fallback chain. There are no default merchant credentials, public signup, password-reset endpoints or new merchant-administration HTTP API.

### 10.3 Authentication and authorization solve different problems

Authentication establishes an identity from credentials. Authorization decides which records/actions that identity may access. A successful password check does not authorize every payment UUID.

`MerchantCredentialRepository.load` reads a merchant's UUID, exact username, BCrypt verifier and enabled status. `DaoAuthenticationProvider` checks those credentials and issues `MerchantPrincipal`, which carries the database UUID and `ROLE_MERCHANT`. A role label on an ordinary principal is insufficient for the merchant chain. `MerchantContext` repeats this typed-principal/authority requirement at the service boundary.

`MerchantPaymentService` obtains its identity from Spring Security's request context. It never accepts a merchant UUID from a body, query parameter or `X-Merchant-Id` header. A merchant reference such as `ORDER-123` is a business reference, not an account identity.

**Alternatives:** trusting a client-supplied merchant ID would let a caller select another tenant. Inferring ownership from merchant references would mix non-unique order labels with identities. Authentication without record ownership checks would still expose cross-merchant data. A server-issued principal plus durable ownership directly expresses the intended boundary.

### 10.4 Why Basic authentication and BCrypt were chosen

The project already includes Spring Security and BCrypt for its operator boundary. This milestone reuses the libraries with a separate database-backed merchant provider. Basic authentication avoids introducing token issuance, signing keys, refresh flows or a pretend identity provider before the underlying ownership rules work.

BCrypt stores a password verifier rather than plaintext. Merchant usernames are case-sensitive ASCII identifiers of 1–100 characters; accepted characters are letters, digits, dot, underscore and hyphen, starting with a letter/digit. Password verifiers must use the permitted BCrypt representation/cost; the database rejects plaintext. The runbook recommends a trusted generator and an appropriate cost, with test cost 4 used only for disposable fixtures.

**Costs:** Basic carries the original credential on each request and needs HTTPS outside loopback. Each request incurs a database lookup and BCrypt work. No rate limiting, MFA, password-recovery service, OIDC/OAuth integration or credential-management audit workflow is added. Do not interpret this as completed production identity management.

**Alternatives:** JWTs can decouple services from a password store, but introduce token trust/lifetime/revocation and key rotation. A conventional login session reduces repeated password hashing but needs session invalidation when credentials are disabled/rotated. An external identity provider is a stronger production direction when its lifecycle and operations are available. None replaces payment ownership checks; this step keeps that core problem visible.

### 10.5 Separate authentication providers and filter chains

At the M16 boundary, the servlet application used three ordered chains. Chapter 12 adds the first-priority provider callback chain before these existing boundaries:

```text
1. projection-routing operator paths → configured operator provider + PROJECTION_OPERATOR
2. payment / ledger / merchant paths → local merchant ProviderManager + MerchantPrincipal
3. remaining paths → deny
```

`MerchantSecurityConfiguration` constructs a local `ProviderManager` with its own `DaoAuthenticationProvider`. It has no parent fallback to operator credentials. The existing operator user store remains independent.

**Example:** a database merchant and configured operator may have the same username with different passwords. The merchant password cannot authenticate to the operator API, and the operator password cannot authenticate as that merchant. An operator role alone also cannot enter the merchant service.

**Alternative:** one combined username/password store would blur operator and merchant identities and risk unintended provider fallback or role crossover. Separate realms make ownership and privilege easier to audit, at the cost of two credential lifecycles. This is not an organization-wide IAM system.

The architecture follows Spring's support for [local authentication managers and providers](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html). Current application behavior is verified by real-credential integration tests, including the deliberately identical username case.

### 10.6 Fresh authentication and CSRF can coexist

The merchant chain does not persist or recover authentication from an HTTP session. `SessionCreationPolicy.STATELESS` and `NullSecurityContextRepository` require credentials on each request. Disabling an account or rotating its verifier is therefore observed on its next authenticated request, including requests carrying an old cookie. An already in-flight operation can still complete; there is no cancellation/fencing protocol for administrative credential changes.

CSRF protection remains enabled. A browser can send cached Basic credentials automatically, so removing authenticated-session persistence does not remove that risk. Spring documents [Basic authentication's CSRF exposure](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html).

`GET /api/v1/merchant/csrf` returns token/header metadata and establishes a CSRF session cookie. Writes require that token/cookie pair **and** merchant credentials. The cookie stores CSRF state, not a reusable merchant login. GET `/me` with only that cookie is unauthenticated.

**Alternatives:** disabling CSRF just because a chain is called stateless would ignore browser credential behavior. Retaining authentication in the CSRF session would make disablement dependent on session invalidation. The selected arrangement preserves the existing token/session workflow while checking credentials afresh. Session expiration/restart requires a fresh CSRF token; multi-instance CSRF-session operation is not validated here.

### 10.7 Durable payment ownership

V16 adds `payments.merchant_id`, a foreign key to `merchants`. `Payment.create(UUID, ...)` records the authenticated identity; JPA marks the field non-updatable, and a PostgreSQL trigger rejects ownership reassignment through SQL as well.

`MerchantPaymentService` checks `PaymentRepository.existsByIdAndMerchantId(paymentId, merchantId)` before payment reads, transitions, journal/event reads and routed projection reads. Foreign and missing UUIDs both return the existing `404 PAYMENT_NOT_FOUND` contract. The existing financial service then retains its transaction, optimistic-locking, journal and outbox behavior.

**Why immutable ownership:** a separate check followed by use would be unsafe if ownership could change between them. Here ownership cannot be reassigned through the normal application/database protocol. New merchant create joins the same transaction as claim, payment and outbox writes. A late write failure rolls back all of them.

**Alternatives:** controller-only checks are easy to omit on another endpoint. A global servlet filter can authenticate a caller but does not know each query's ownership semantics. Rewriting all financial logic into merchant controllers would duplicate tested transaction paths. The merchant service boundary centralizes authorization and delegates existing correctness-sensitive work.

The older global `PaymentService` methods intentionally remain trusted internal operations for existing tools/recovery/tests. New HTTP controllers inject `MerchantPaymentService`. This is application-enforced isolation over a shared database, not PostgreSQL row-level security or isolation from privileged database users.

### 10.8 Merchant-scoped idempotency without changing retry semantics

The key changes from one global primary key to a composite primary key:

```text
idempotency_records PRIMARY KEY (merchant_id, idempotency_key)
```

`IdempotencyRepository.claim(UUID, ...)` uses `INSERT ... ON CONFLICT (merchant_id,idempotency_key) DO NOTHING`. The affected row count still determines claim versus replay. The separate SELECT at Read Committed still sees the winning committed result after a concurrent conflict.

The logical request fingerprint remains unchanged: merchant reference, normalized money and currency. Merchant identity is represented by the database key, rather than mixed into a string label. A deferred composite foreign key from `(merchant_id,payment_id)` to `payments(merchant_id,id)` prevents a claim from naming another merchant's payment while retaining claim-before-payment insertion in one transaction. Accepted claim snapshots now reject UPDATE/DELETE.

**Example:** A/key and B/key both create once. A/key with a different amount returns 409. A/key after capture/refund returns A's original CREATED snapshot, as before. Concurrent identical requests for A produce one 201 and one 200; two merchants racing on the same client key can both get 201.

**Alternatives:** a UUID prefix or hash concatenated into the existing key would encode tenancy indirectly and complicate length/legacy collision rules. A second idempotency table would duplicate the retry protocol. Putting claims in Redis would add a cross-resource consistency problem before a Redis-specific need exists. Composite PostgreSQL identity keeps the existing atomicity model explicit.

### 10.9 Ledger isolation without rewriting historical journals

Account definitions remain the existing pooled `PROCESSOR_CLEARING` and `MERCHANT_PAYABLE` definitions per currency. There is no new wallet, account hierarchy or merchant balance entity.

`LedgerRepository.merchantBalances` joins entries → transactions → payments and includes only the authenticated payment owner. It returns posted account totals for that merchant. A merchant with no journals gets an empty list even when another merchant has accounts/entries; this avoids exposing other merchants' activity through empty-account differences. Capture/refund still create balanced immutable two-entry journals.

**Example:** A has captured 10 INR and B has captured 999 INR. A sees 10 in each relevant normal balance, not 1009. Refunding A makes A's normal balances zero and leaves B at 999.

**Alternatives:** authenticating the existing global aggregate would still leak other tenants' sums. Filtering only the response cannot unmix an already global sum. Introducing tenant-specific physical accounts now would require a larger accounting migration and business model. Deriving owned totals preserves history while providing scoped reads; chapter 14 later adds explicit capture fees, while settlement accounts and payable disbursements remain unimplemented.

### 10.10 Upgrade existing records honestly

Existing records do not establish a trusted merchant account association. V16 assigns old payments and claims to the reserved all-zero UUID `legacy-internal`. That identity is disabled, has no verifier, cannot be enabled by ordinary SQL and is explicitly excluded from credential loading. Original payment/claim fields and response snapshots remain unchanged.

Trusted headless/legacy creation methods keep this scope as their default. New HTTP credentials always come from a provisioned non-legacy merchant. There is no public adoption/reassignment endpoint or automatic reassignment to the first account created.

**Alternatives:** making all historical rows belong to the first user would expose data without ownership evidence. Deleting them would lose financial/retry history. Making the new field nullable would introduce an ambiguous anonymous access path into every query. A sealed legacy identity preserves data and makes the access limit explicit.

This migration also breaks anonymous API access intentionally. Clients must provision credentials and send Basic plus CSRF for writes. Old binaries are not supported for concurrent operation after the composite-key migration; their SQL targets a different uniqueness contract. Follow a coordinated application upgrade.

### 10.11 Events and operational scope stay explicit

The retained v1 payment event and response schemas do not gain merchant identity fields in this milestone. Existing immutable outbox/receipt envelopes are not rewritten. Merchant HTTP projection/event reads use authoritative co-located payment ownership, rather than trusting a consumer snapshot to grant access.

A source-less consumer event may still produce an operational projection, as the existing consumer contract allows. It does not invent a merchant owner. Such a projection is readable by trusted internal services but is refused by the merchant HTTP API. An owned payment awaiting projection has the separate `PAYMENT_PROJECTION_NOT_FOUND` result.

**Tradeoff:** avoiding a wire-contract rewrite preserves retained event/replay semantics now, but a separately deployed tenant-aware consumer would need a deliberate versioned ownership contract. Kafka/database access remains trusted infrastructure; securing that boundary and multi-broker operation are separate work. The operator API remains cross-merchant privileged access, not a merchant analytics API.

### 10.12 Actual request flow

```text
POST /api/v1/payments + Basic credentials + CSRF token/session + Idempotency-Key
→ MerchantSecurityConfiguration / DaoAuthenticationProvider
→ MerchantCredentialRepository.load → MerchantPrincipal
→ PaymentController.createPayment
→ MerchantPaymentService.createPayment → MerchantContext.merchantId
→ PaymentService.createPayment(merchantId, key, request)
→ IdempotencyRepository.claim / PaymentRepository.saveAndFlush / OutboxService.record
→ one PostgreSQL transaction
→ CreatePaymentResult → PaymentResponse (201, or 200 for exact replay)
```

```text
GET /api/v1/payments/{paymentId}/events
→ authenticated merchant principal
→ PaymentEventController.getPaymentEvents
→ MerchantPaymentService.getPaymentEvents / owned-payment check
→ OutboxService.getPaymentEvents
→ scoped authorized response, or 404 before reading another merchant's events
```

## 11 Milestone 17 Durable sandbox provider integration

This chapter introduces the full-amount remote command path. Chapter 12 adds authenticated callback completion; chapter 14 introduces a separate local amount mode that cannot mix with it.

### 11.1 Why this milestone matters

Earlier milestones made local payment transitions, journals and events reliable. Milestone 16 established the merchant that owns each payment. Neither meant that an external payment system had authorized or captured anything: the manual transition endpoints only changed our database.

Milestone 17 adds a separate sandbox provider process reached through real HTTP. The application now commits an intention, asks that process for a decision, and applies a confirmed result to the existing payment/ledger/outbox transaction. This introduces the central distributed-payment problem: the other system may succeed even when our application receives no usable response.

**Example:** the sandbox accepts a capture and commits its decision. Its reply is deliberately cut short. Our database must keep the payment AUTHORIZED and the command UNKNOWN. Calling that a decline would be false; creating another capture command could duplicate the external operation. Retrying the original command recovers the saved provider result and posts one local capture journal.

This is an educational local sandbox, with no card data, vendor account or real funds. The adapter boundary is useful preparation for a vendor integration, but a real vendor's authentication, idempotency, status and webhook contracts still require their own implementation.

### 11.2 What was added

- Merchant-owned AUTHORIZE, CAPTURE and full REFUND commands through a new provider-operation API.
- A Java HTTP adapter and independently runnable, authenticated loopback sandbox with a restartable decision journal.
- Durable operation identity, frozen intent, provider binding, attempts and progress in PostgreSQL.
- Exact retries after lost replies, lease expiry or a local completion rollback, using the original provider idempotency key.
- Safe owned progress/detail reads and refusal of manual transitions on provider-managed payments.
- Confirmed outcomes reuse the established payment, balanced-ledger and transactional-outbox paths.

Provider use is disabled by default. Existing unbound manual teaching flows continue to work. Enabling integration does not automatically enroll old payments or dispatch unfinished commands in the background.

### 11.3 The important identities

| Identity | Owner and meaning |
| --- | --- |
| Merchant UUID | Comes from the authenticated `MerchantPrincipal`; never selected by request payload. |
| Payment UUID | Existing merchant-owned payment and frozen amount/currency. |
| Request UUID | Merchant's logical command identifier; unique across that merchant's payments. |
| Operation UUID | Server-generated durable command identifier; sent unchanged as provider `Idempotency-Key` on every attempt. |
| Provider instance UUID | Durable sandbox namespace, saved in the binding, command and sandbox journal. |
| Lease token | Fresh internal UUID per attempt, used to reject completion by an older worker; omitted from HTTP views. |
| Provider payment/operation IDs | Validated remote references returned by this sandbox protocol. |

These IDs solve different problems. A new lease token is appropriate for a new worker attempt. A new provider operation UUID is unsafe for retrying the same uncertain effect. A merchant request UUID alone is also unsuitable as a global provider key because two merchants may use the same value.

The existing payment-creation `Idempotency-Key` and the new provider command `requestId` are separate namespaces. Creating a payment and authorizing it are different logical operations.

### 11.4 Actual request flow and transaction boundaries

```text
POST /api/v1/payments/{paymentId}/provider-operations
  → merchant Basic authentication + POST CSRF
  → ProviderOperationController.submit
  → ProviderOperationService.submit                 [NOT_SUPPORTED]
      → ProviderOperationTransactions.prepare        [REQUIRES_NEW, READ_COMMITTED]
          → owned source lock, exact intent/binding → commit READY
      → ProviderOperationTransactions.claim          [REQUIRES_NEW, READ_COMMITTED]
          → attempt + lease token/deadline           → commit IN_FLIGHT
      → HttpSandboxPaymentProvider.execute           [no database transaction]
          → POST sandbox /v1/operations
          → SandboxProviderStore.execute             [persist/replay remote decision]
      → ProviderOperationTransactions.finish         [REQUIRES_NEW, READ_COMMITTED]
          → validate identity + fence token + source version/state
          → PaymentService.applyProviderResult
          → payment + capture/refund ledger + OutboxService.record
          → commit operation and local effects together
  → ProviderOperation.Result
```

If the provider response cannot be trusted or obtained, `ProviderOperationTransactions.uncertain` records UNKNOWN in its own transaction. It does not fail the payment or invent a journal.

`NOT_SUPPORTED` suspends an inherited caller transaction. The separate proxied transaction bean makes `REQUIRES_NEW` effective; calling annotated private/self methods would not provide the same Spring proxy boundary. An integration test invokes the route inside an older Repeatable Read transaction, then rolls the caller back: the confirmed provider completion remains committed, and HTTP still runs outside a transaction.

Suspending a caller transaction does not commit it or release its existing connection/locks. Callers should avoid holding financial locks around this orchestration. The guarantee is that provider I/O joins no active Spring transaction and that each provider database stage owns its separate commit, not that arbitrary callers cannot retain unrelated resources.

**Why avoid one transaction around HTTP?** It would retain locks and a connection while waiting on another process, yet rolling PostgreSQL back could never undo a provider decision. Short independent database transactions make that gap explicit and recoverable. The cost is additional states and recovery logic.

### 11.5 Command state versus payment state

| Operation state | Meaning and permitted next step |
| --- | --- |
| READY | Intent is committed; a caller may claim an attempt. |
| IN_FLIGHT | One attempt owns a lease; another caller sees progress until expiry. |
| UNKNOWN | No confirmed usable outcome; retry the exact original command. |
| APPLIED | Confirmed approval and corresponding local financial/state effect committed. |
| DECLINED | Confirmed provider decline committed; local effect depends on action. |
| REQUIRES_REVIEW | Provider outcome is known, but local source no longer matches frozen intent; no automatic application or new command. |

```text
READY → IN_FLIGHT → APPLIED / DECLINED / REQUIRES_REVIEW
             └──→ UNKNOWN → IN_FLIGHT
      expired IN_FLIGHT → IN_FLIGHT with a fresh token
```

Progress HTTP 200 means the command was processed or found; inspect `operation.state`, `providerOutcome` and `localEffectApplied`. HTTP 200 does not certify approval. Historical exact retries return the stored command even after later payment transitions, provider disablement or instance configuration change.

| Confirmed result | Local payment/ledger effect |
| --- | --- |
| AUTHORIZE approved | CREATED → AUTHORIZED; event, no financial journal. |
| AUTHORIZE declined | CREATED → FAILED; event, no financial journal. |
| CAPTURE approved | AUTHORIZED → CAPTURED; one balanced capture journal and event. |
| CAPTURE declined | Remains AUTHORIZED; no journal/event for a nonexistent state transition. |
| REFUND approved | CAPTURED → REFUNDED; one reversal journal and event. |
| REFUND declined | Remains CAPTURED; no reversal/state event. |
| Any uncertain transport/protocol result | Original payment status remains; no new financial effect. |

`localEffectApplied` is therefore true for a declined authorization but false for a declined capture/refund. A confirmed capture/refund decline releases the active-command gate and permits a genuinely new request UUID for another attempt. An uncertain command keeps that gate closed.

### 11.6 Idempotency, concurrency and fencing

`ProviderOperationRepository` stores immutable merchant/payment/action/scenario/amount/currency/source-version/provider-instance intent. Reusing a request UUID with another payment, action or scenario returns 409. The same UUID under another merchant is independent.

A partial unique index allows one unfinished command per payment, including UNKNOWN and REQUIRES_REVIEW. Row locks and nonwaiting acquisition serialize preparation without leaving partial bindings. PostgreSQL SQLSTATE `55P03` is recognized as a busy conflict even when Spring wraps it as `UncategorizedSQLException`; callers receive 409 and can retry the exact command.

Each claim commits an incremented attempt, random lease token and database-clock deadline. Concurrent exact submissions cannot both claim an unexpired lease. A crashed worker leaves a recoverable IN_FLIGHT record. After expiry, another caller claims a new token and resends the same provider key. A delayed old worker cannot complete using its old token.

This is **fencing**, not proof that a remote request stopped. Two remote invocations can overlap after a lease expires. The sandbox's durable exact-intent idempotency makes them resolve to one decision. The local completion transaction and existing unique journal constraints prevent duplicate local effects. There is no blanket network-level exactly-once guarantee.

The default lease is 30 seconds and request deadline 2 seconds. Validation requires at least a two-second margin; supported bounds are 5–120 seconds for leases and 100–10,000 milliseconds for HTTP. A command has a maximum of 1,000 recorded claims. Exhaustion requires review; this milestone has no administrative reset endpoint.

### 11.7 Provider binding and guarded local application

The first provider AUTHORIZE on a CREATED payment commits a binding to the configured provider instance. Enrollment is irreversible through the API. It prevents manual authorize/capture/fail/refund from bypassing the provider, including when the first provider result is UNKNOWN. There is no cancel/void workflow yet.

CAPTURE and REFUND require a confirmed provider authorization/reference and matching instance. A manually authorized payment cannot be adopted halfway through. New or retried unfinished work requires provider integration enabled and the original configured instance. Completed historical commands remain readable/replayable while disabled.

The instance UUID identifies the provider journal's namespace, not just a URL. Keeping the same UUID while discarding its journal destroys remote idempotency history. Restore the original journal; assigning the old UUID to an empty store is not a recovery procedure. URL or API-key changes can retain the same namespace only when they still reach that original history.

`PaymentService.applyProviderResult` requires an existing transaction and an operation authorized in that very PostgreSQL transaction. V17 records `application_xid`, and database triggers tie payment changes to the matching command, source version/status and expected target. Deferred checks require the resulting payment/version, outbox and authorization binding to agree at commit. Existing balanced-ledger constraints remain active.

Application checks make normal mistakes readable; database checks protect ordinary direct SQL and bypassed service paths. They do not constrain a privileged database administrator who can disable triggers or rewrite evidence. A source mismatch produces REQUIRES_REVIEW rather than silently overwriting local state. No force-apply tool is provided.

Those guards require a consistent local command/payment/event transaction; they are not cryptographic proof of a remote approval. The application and its database access remain trusted. The adapter trusts the configured local sandbox and validates response correlation; the loopback HTTP demo has no signed response attestation. Verified callbacks have their own boundary in chapter 12.

### 11.8 HTTP adapter and strict protocol

`PaymentProvider` separates orchestration from transport. `HttpSandboxPaymentProvider` is the only implementation here. Java 21 `HttpClient` avoids adding a vendor SDK before a vendor contract exists. The configured address must be loopback; the sandbox binds IPv4 loopback. Redirects and proxy use are disabled so the local credential is sent directly to the intended endpoint.

Requests use a shared Bearer key and operation UUID header. `ProviderWire` defines a versioned closed JSON contract, caps documents at 8 KiB, and rejects malformed UTF-8, duplicate/trailing/unknown fields, missing data and numeric coercion. Responses must match operation, payment, merchant, instance, action, amount, currency and expected remote references. Transport success alone is insufficient evidence to change money.

`sandboxScenario` is an explicit local test control. A future live adapter must derive approval from the authenticated provider's contract; a merchant-supplied scenario must never become authority to approve a real transaction.

The adapter uses an asynchronous request plus a bounded wait for the complete response body and a capped body subscriber. A server that sends headers but never finishes its body still reaches the application deadline; a test requires refusal within two seconds with a configured 200 ms limit. Invalid/oversized replies, redirects, connection failure and incomplete responses all become sanitized uncertainty.

Java's request timeout and client lifecycle are documented in the official [HttpRequest.Builder](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html) and [HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html) APIs. Our complete-body deadline and response-identity checks are explicit adapter behavior, not assumptions about a real vendor.

### 11.9 Separate sandbox process and persistent decisions

`java -jar ... provider-sandbox` dispatches before Spring, so it requires no PostgreSQL, Kafka or application credentials. `SandboxProviderServer` exposes only POST `/v1/operations` with the shared key and matching idempotency header. Options are exactly `--port`, `--data-dir` and `--instance-id`; port 0 selects an available test port.

`SandboxProviderStore` holds a single-writer file lock, persists its instance identity and records ordered decisions as separate JSON files. It computes the next remote payment state, force-writes a temporary file, atomically publishes it, then updates memory. Startup validates and replays the complete ordered journal; missing sequence, corruption or another instance is refused. A second process cannot own the same directory concurrently.

The journal supports at most 10,000 decisions, each bounded to 8 KiB. The HTTP server uses four workers; this is a small teaching service without production admission/load guarantees. On unsupported atomic publication it refuses rather than silently choosing weaker writes.

`TIMEOUT_AFTER_ACCEPT` is deliberately named for an uncertain accepted outcome. On a new command the server persists the decision, advertises the complete response length, sends only a short prefix and closes. An exact retry returns the full persisted response. The packaged test kills and restarts the sandbox between those requests, proving recovery across that process boundary.

This demonstrates process-restart persistence on the tested local filesystem. It does not certify sudden power-loss durability, remote filesystems, independent backups or cryptographic authenticity. Java documents the guarantees and possible failures of [atomic file moves](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html) and [FileChannel.force](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html); directory durability and external restore verification remain outside this implementation.

### 11.10 Important failure cases

| Failure | Durable result and recovery |
| --- | --- |
| Crash after prepare, before claim | READY remains; retry original command. |
| Crash after claim, before/after network | IN_FLIGHT remains; wait for lease expiry and retry original command. |
| Provider accepted, response lost | UNKNOWN; unchanged payment/journal; exact retry recovers one saved provider decision. |
| Invalid identity or money in response | UNKNOWN; no local application. |
| Provider approved, local outbox insert fails | Completion transaction rolls back payment, ledger and operation completion; persisted lease remains. Retry after expiry recovers the remote result. |
| Old worker returns after another claim | Old token ignored; cannot overwrite current progress. |
| New command submitted while unresolved | 409; recover the existing command, do not invent another key. |
| Provider disabled or instance changed | New/unfinished execution refused; history still accessible. |
| Journal missing/corrupt or capacity exhausted | Sandbox refuses work; application treats an unconfirmed reply as UNKNOWN. Preserve evidence and restore correct history. |
| Confirmed provider result conflicts with source | REQUIRES_REVIEW; no automatic local effect, force repair or replacement command. |

The M17 retry path is driven by the merchant resubmitting the original POST. Chapter 12 adds a webhook receiver that can complete the existing command. Provider polling, an automatic command-recovery scheduler and timeout-to-decline conversion remain absent.

### 11.11 Alternatives and why these choices fit this milestone

| Chosen approach | Alternative and tradeoff |
| --- | --- |
| Persist intent before HTTP, finish separately | One long transaction is simpler to read but holds locks and cannot atomically commit with an independent provider. Durable orchestration requires more states. |
| Stable operation UUID plus exact provider replay | New key after timeout might duplicate a remote effect. Safe replay depends on retaining the provider namespace/history. |
| UNKNOWN distinct from DECLINED | Treating all errors as failure gives a tidy response but makes an unjustified financial claim. Uncertainty requires visible recovery work. |
| Lease token plus remote idempotency | A process-local lock cannot coordinate instances/restarts. A lease alone cannot stop an already sent request. Two complementary mechanisms are needed. |
| First-authorization binding | Mixing manual and provider transitions permits local/remote divergence. Binding limits flexibility until void/cancel and reconciliation exist. |
| Java HTTP adapter with a separate local sandbox | In-process mocks miss sockets, reply loss and process restart. A live vendor SDK introduces account/contract work beyond this local milestone. Vendor-specific adapters remain a valid later choice. |
| PostgreSQL durable state and guards | Redis-only command ownership would separate recovery authority from the financial database. Redis may later support risk/rate controls, not replace these committed records. |
| Single-writer bounded file journal for sandbox | In-memory decisions vanish on restart. Another production database would add operational scope to a teaching provider. Files impose capacity, filesystem and single-process limits. |
| Merchant-driven retry now | A queue/scheduler can automate recovery, but needs backoff, ownership, shutdown and operational controls. Explicit retries make the current contract testable first. |
| Reuse existing financial/outbox services | Duplicating capture/refund posting inside the adapter risks inconsistent ledger rules. Shared paths retain earlier invariants but require careful transaction authorization. |

These are contextual choices. A production provider, higher load, multi-region operation or richer payment lifecycle could justify other tools; those alternatives are not universally worse.

## 12 Milestone 18 Verified incoming provider callbacks

A remote result can arrive after the original HTTP response is lost. This receiver authenticates and applies results only within the authority of an existing frozen provider command.

### 12.1 Why incoming callbacks are the next block

Milestone 17 made an independent sandbox decision recoverable through an unchanged provider operation UUID. If an accepted capture lost its HTTP reply, the merchant could resubmit the original command and retrieve that decision. Recovery still depended on another outbound request.

A webhook lets the provider initiate delivery of its already committed result. That improves the same uncertain-outcome problem without creating a second financial authority. The callback must identify an existing command, authenticate its sender, match frozen intent and commit through the same payment/ledger/outbox rules.

**Example:** the sandbox accepts an authorization but cuts its response short. The payment remains CREATED and the command becomes UNKNOWN. Its signed callback arrives later. The receiver records the event and confirms the original authorization in one database transaction. A later HTTP retry or duplicate callback finds that result and does not authorize again.

This is still a local sandbox. It introduces no live vendor/card account, real money or merchant notification delivery.

### 12.2 What is now available

- POST `/api/v1/provider-webhooks/sandbox`, protected by raw-body HMAC and a fresh delivery timestamp.
- Durable exact event identity and receipt evidence, with safe duplicate/conflict behavior.
- Confirmation of existing UNKNOWN or IN_FLIGHT commands, including when outbound execution is disabled.
- Late/out-of-order result handling that never rewinds a later payment state or repeats a journal.
- Owned receipt metadata under GET `/api/v1/payments/{paymentId}/provider-operations/{operationId}/webhooks`.
- Optional automatic sandbox callbacks and authenticated redelivery from its retained decision journal, including after process restart.

The incoming receiver and sandbox sender both default off. They use a separate signing secret from the outbound provider API key. Existing M17 behavior remains available with callbacks disabled.

### 12.3 Three identities that must not be confused

| Identity | Purpose |
| --- | --- |
| Provider operation UUID | Original durable financial command, unchanged across outbound retries and callbacks. |
| Provider event UUID | Logical notification identity, unique within the provider instance; deduplicates receipts. |
| Receipt UUID | Application-generated identifier for the first accepted event body. |

The sandbox derives a stable event UUID from its instance/operation identity. Re-delivery after restart therefore retains the same event/body. The receiver also accepts a distinct event UUID describing the same result: it records ALREADY_RECORDED without applying the operation again.

A delivery timestamp is different from an event or operation identity. It changes when an old logical event is freshly delivered. It establishes freshness of this authenticated delivery, not business ordering. Do not compare delivery timestamps to decide whether to refund, capture or rewind a payment.

### 12.4 Exact signed protocol

`ProviderWebhook.Event` contains `version`, `eventId` and a nested M17 `ProviderOperation.Response`. The result includes instance, operation, payment, merchant, action, amount, currency, outcome and provider references. No merchant reference, password, card data or arbitrary vendor payload is included.

Three headers are required, exactly once each:

| Header | Value |
| --- | --- |
| `Fincore-Webhook-Key-Id` | Configured nonsecret key ID; default `sandbox-v1`. |
| `Fincore-Webhook-Timestamp` | Unix seconds for this delivery, with canonical positive decimal digits. |
| `Fincore-Webhook-Signature` | `v1=` followed by 64 lowercase hexadecimal characters. |

The signed byte sequence is:

```text
ASCII("fincore-provider-webhook-v1\n" + keyId + "\n" + timestamp + "\n")
    concatenated with the exact raw UTF-8 request body

signature = "v1=" + lowercaseHex(HMAC-SHA256(signingKey, signedBytes))
```

`ProviderWebhookVerifier` checks configuration and headers, reads at most 8 KiB plus one byte, compares the MAC with `MessageDigest.isEqual`, checks freshness again after body verification, then parses the closed protocol. It never parses a reserialized approximation of what was signed.

Whitespace, numeric spelling and field order are part of the signed bytes. Changing them requires a new valid MAC. Reusing an already accepted event identity with changed bytes still conflicts, even when the JSON would have the same meaning.

The default tolerance is inclusive ±300 seconds; configuration supports 30–900 seconds. Rechecking after body reading prevents a delivery that expired during ingestion from being accepted merely because its headers were initially fresh. A fresh retry signs the same event body using a new current delivery timestamp.

HMAC authenticates message integrity using a shared secret; it does not encrypt the payload or provide public-key nonrepudiation. See the primary definition in [RFC 2104](https://www.rfc-editor.org/info/rfc2104/). Anyone holding this signing key can construct a valid MAC, so the key is a trust boundary, not a merchant credential.

### 12.5 Strict parsing and bounded money validation

`ProviderWebhookWire` reuses the strict versioned JSON reader: invalid UTF-8, duplicate/trailing/unknown fields, missing values and coercion are refused. Event/financial identities must be nonzero UUIDs. The sandbox reference formats and positive `NUMERIC(19,2)` money limits must match the protocol before database access.

Money is checked without expanding an attacker-chosen decimal exponent into a huge integer. `ProviderWire.validAmount` strips trailing zeros and inspects precision/scale using bounded arithmetic. A nonzero third decimal place, oversized integer amount, negative amount or extreme positive/negative exponent is rejected. Trailing zero representations such as `10.000` remain logically exact; frozen command comparison still uses monetary equality.

The receiver verifies the signature before JSON interpretation and all database work. An oversized body is refused before expensive processing. This caps application payload handling; it does not supply production ingress timeouts, rate limiting or denial-of-service protection by itself.

### 12.6 Authentication is separate from merchant access

`ProviderWebhookSecurityConfiguration` has its own first-priority filter chain matching only the exact callback path. POST reaches the HMAC verifier without merchant Basic credentials or browser CSRF. Other methods are denied. The route does not persist authentication in a session.

Merchant and projection-operator chains retain their previous credentials, scope and CSRF requirements. A valid merchant password does not authenticate a callback. A valid callback MAC does not authorize a merchant payment request. The receipt-list GET still requires actual merchant credentials and source-payment ownership.

This separation follows Spring Security's [multiple filter-chain matching](https://docs.spring.io/spring-security/reference/6.5/servlet/configuration/java.html). Its normal [CSRF protection](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html) remains on browser-credential write surfaces; the exception is deliberately confined to this machine-authenticated route.

The provider instance UUID and one active signing key ID are pinned by receiver configuration. A valid MAC with another instance is refused. This milestone does not add a key ring, overlap rotation policy or live-vendor signature format. Coordinate key changes in the sender and receiver; a new valid key can authenticate a duplicate body without rewriting its original receipt metadata.

### 12.7 Actual request flow and commit boundary

```text
SandboxProviderStore persists original provider decision
  → SandboxWebhookSender.deliver creates stable event + fresh HMAC headers
  → POST /api/v1/provider-webhooks/sandbox
  → ProviderWebhookController.receive
      → ProviderWebhookVerifier.verify                 [before database work]
      → ProviderWebhookService.accept                 [REQUIRES_NEW, READ_COMMITTED]
          → find matching existing command
          → ProviderWebhookRepository.claim           [unique instance/event]
          → ProviderOperationTransactions.finishWebhook [MANDATORY, same transaction]
              → lock owned source + operation
              → compare frozen intent and existing outcome
              → apply existing payment/ledger/outbox path if eligible
          → ProviderWebhookRepository.finish
          → commit receipt + operation + payment + journal + outbox together
  → ProviderWebhook.Acknowledgement
```

No callback acknowledgement is returned before this transaction commits. There is no separate inbox commit followed by an unimplemented worker. If processing or commit fails, the receipt and financial completion roll back together; the sender must retry.

Claim insertion uses PostgreSQL uniqueness with `ON CONFLICT DO NOTHING`. A concurrent duplicate waits for the first insertion to commit or roll back. After commit, it reads the saved exact body/disposition. If the first transaction rolls back, the contender can become the owner. Source and operation locks use NOWAIT so competing financial work produces a retryable failure rather than retaining a partial receipt.

The callback performs no outbound provider call inside its transaction. `REQUIRES_NEW` gives it a fresh commit even if a trusted internal caller has an older ambient transaction. The existing M17 orchestrator still suspends active participation around provider HTTP work.

### 12.8 Receipt dispositions and HTTP acknowledgement

| Disposition | Meaning |
| --- | --- |
| CONFIRMED | Existing dispatched command was confirmed and its documented local effect committed; the provider outcome may still be a decline. |
| ALREADY_RECORDED | Same confirmed result already exists; no additional financial/state application. |
| REQUIRES_REVIEW | Confirmed result is retained, but frozen source consistency failed; no forced financial application. |
| UNMATCHED | No command matching the claimed merchant/payment/instance identity exists; no payment is created or adopted. |
| REJECTED | Known intent differs, outcome contradicts history, or command was never dispatched. |

HTTP 200 means a verified event has a durable final receipt. It does not mean the payment was approved, captured or automatically repaired. The acknowledgement contains receipt/event UUIDs, disposition and `duplicate` only. Inspect the operation's provider outcome/current payment and the owned receipt metadata when diagnosing it.

An exact duplicate returns its original receipt/disposition with `duplicate:true`. A fresh event describing an old confirmed result gets its own ALREADY_RECORDED receipt. A changed body under the same instance/event UUID gets 409 and preserves the first receipt unchanged.

UNMATCHED and REJECTED receipts are acknowledged for diagnosis, not queued for later automatic repair. Replaying the same body returns the original disposition. There is no API to relink, rewrite, force-apply or reprocess them. Unknown commands are impossible for the normal legitimate flow because intent commits before the provider request; an unmatched event indicates a namespace/history/configuration problem requiring investigation.

### 12.9 Safe application, races and ordering

#### A callback after UNKNOWN

V18 permits UNKNOWN → APPLIED/DECLINED/REQUIRES_REVIEW only when a matching receipt is being processed in the same PostgreSQL transaction. It does not manufacture another outbound claim, consume the retry budget, or contact the provider again. Outbound integration may be disabled while this separately enabled, correctly signed receiver confirms an existing command.

#### A callback during IN_FLIGHT

The callback can complete the current command under the ordinary payment/operation locks. Completion clears its lease. When the HTTP worker later returns, `finish` finds a terminal state and returns it without a second effect. A late `uncertain` call also leaves that result intact. The callback and synchronous response must agree with frozen intent; a contradictory terminal notification is rejected rather than overwriting history.

#### A callback for an older transition

After a payment is REFUNDED, an original AUTHORIZE or CAPTURE confirmation may arrive. The corresponding command already contains its terminal result, so the callback is recorded as ALREADY_RECORDED. It does not compare delivery timestamps to move the payment backwards. Integration tests deliver refund, capture and authorization notifications in reverse order and retain one capture plus one reversal.

#### A callback for READY

A merely prepared command has never been claimed for dispatch. It is rejected with NOT_DISPATCHED. A signed message cannot create a payment, attach an unrelated source or bypass command enrollment. Provider-generated scenarios are still local sandbox test controls, never authority for a live-vendor approval.

#### Source mismatch or local failure

Frozen source version/status/amount/currency still gate application. Detected drift retains REQUIRES_REVIEW without a journal. If the outbox or receipt finalization fails after financial work, the whole transaction rolls back. Retrying the same signed event with fresh delivery headers can then complete once; an HTTP failure must not be interpreted as a provider decline.

### 12.10 V18 schema and local evidence guarantees

`provider_webhook_receipts` stores exact bounded body bytes and SHA-256, normalized result identities/money/references, first accepted key ID/delivery timestamp, receipt time, matched operation and final disposition/reason. The secret and request MAC are not retained. Public metadata omits raw bodies and credentials.

Database checks bind normalized columns to the raw JSON and digest. A matched operation must agree with merchant, payment and provider instance. A receipt starts RECEIVED and can be finalized once in its insertion transaction; a deferred constraint forbids committing an unfinished receipt. Completed receipts reject UPDATE/DELETE.

The new UNKNOWN completion branch requires a matching RECEIVED row with the same result and current transaction identity. Existing V17 intent immutability, attempt/lease transitions, terminal history, managed-payment guards and financial/outbox checks remain. Earlier migration files are unchanged. Upgrade tests preserve M17 merchant/payment/retry/outbox/binding/command before-images and start with no receipts.

These controls provide consistent local audit evidence. They are not independent proof that a historical MAC was checked, external storage attestation or protection against a privileged database administrator. The trusted verifier/application/database remain part of the boundary; no RLS or cryptographic database ledger is introduced.

### 12.11 Sandbox delivery and recovery mechanics

The standalone `provider-sandbox` entry point accepts optional callback URL/key/key-ID environment settings. When configured, it queues a callback after a decision has been persisted, including when the original response is incomplete. Provider processing does not roll back when delivery fails.

The sender has one worker and a bounded queue of 64 notifications. It uses the shared direct HTTP transport: loopback receiver path only, no proxy/redirect, two-second complete-response deadline and 8 KiB acknowledgement cap. Acknowledgement identity and shape are validated. Queue rejection or an unconfirmed request emits a sanitized event-ID diagnostic and retains the provider decision.

This queue is in memory and has no retry scheduler or crash scan. The durable decision journal is the source for explicit redelivery. POST `/v1/webhooks/{operationId}/deliver` on the sandbox requires its existing provider API Bearer key, an empty body and a known persisted decision. It signs that decision with a current delivery timestamp; callers cannot supply an invented result body.

After restart with the original directory/instance, this endpoint can redeliver the same event/body without sending another payment command. The packaged-JAR test forcibly kills the sandbox process, restarts it, and verifies that the signed body remains identical. The actual servlet-receiver test also exercises an accepted-but-lost HTTP reply followed by a callback into the running Spring Boot application.

Retaining the journal does not certify sudden power-loss durability or independent backup/restore. Sandbox delivery is a development aid, not Milestone 19's reliable outgoing merchant-notification service.

### 12.12 Alternatives and tradeoffs

| Chosen approach | Alternative and why it was not chosen here |
| --- | --- |
| HMAC over exact bytes plus delivery timestamp | A static bearer token alone does not bind the body or freshness. HMAC requires careful byte framing, clock/key management and shared-secret trust. |
| Dedicated callback security chain | Disabling CSRF across payment APIs would weaken browser-credential protection. One exact machine route keeps boundaries explicit. |
| One transaction for receipt and application | Separately acknowledging an inbox before processing requires a durable worker/recovery contract. Synchronous processing is simpler here but latency/locks can cause retryable failures. |
| Unique instance/event plus exact-body comparison | In-memory deduplication disappears on restart. Event identity alone would hide changed payloads. Persistent evidence consumes storage and needs future retention planning. |
| Existing frozen command and terminal-outcome checks | Trusting only a payment ID or delivery timestamp could adopt another payment or rewind history. Commands make authority narrower but reject unsupported unsolicited events. |
| Shared confirmed-result financial path | Callback-specific posting code could drift from synchronous capture/refund accounting. Reuse keeps invariants together and requires explicit transaction guards. |
| Durable rejected/unmatched dispositions | Silently dropping them loses diagnosis; repeatedly returning failure for a permanent mismatch creates retry storms. Acknowledged evidence needs later controlled investigation rather than automatic repair. |
| Bounded best-effort sandbox queue plus explicit redelivery | A fully durable delivery worker would add retry/backoff/claim/retention operations to a test provider. That larger contract belongs to a dedicated delivery milestone; journal-backed manual recovery makes this limit visible. |
| Separate incoming and outgoing webhook milestones | The provider test sender is enough to exercise ingestion. Real merchant notifications need subscriptions, destination validation, per-tenant secrets, delivery attempts and operational recovery. |

These alternatives are contextual. A live vendor may require asymmetric signatures, key discovery, different event semantics or asynchronous inbox workers. This implementation intentionally implements one local, documented protocol first.

## 13 Milestone 19 Reliable outgoing merchant webhooks

Incoming provider results and outgoing merchant notifications have different identities and retry contracts. This chapter makes the merchant notification obligation durable.

### 13.1 Why merchant callbacks are a separate block

Milestone 18 receives authenticated provider results. Merchants also need to learn when their payments change without polling. This block publishes a merchant-owned notification for each new payment outbox event. It is deliberately separate from the provider callback sender: the merchant destination, signing identity, delivery records, retries, and access boundaries are different.

The target is an end-to-end local learning flow. Destinations are loopback-only, delivery is opt-in, and no public Internet vendor or real money is involved.

### 13.2 What is available

- Authenticated merchant endpoints create, list, pause, and resume webhook subscriptions.
- Each subscription has a generated signing secret returned at creation and on an exact accepted create replay. The application stores it encrypted, not as plaintext.
- A PostgreSQL trigger enrolls every new payment outbox event for active subscriptions in the same transaction as its payment change.
- Delivery requests carry the exact immutable stored JSON body and an HMAC-SHA256 signature with a fresh timestamp.
- A scheduled worker uses bounded leases, HTTP deadlines, retries with capped exponential backoff, attempt history, and a terminal `DEAD` state.
- Merchants can inspect their own delivery/attempt metadata and explicitly retry a dead delivery with a revision-like retry generation and reason.
- A runnable local sink verifies signatures, durably deduplicates delivery IDs, and supports a repeat demo after process restart.

Subscriptions created after an event do not receive that historical event. A paused subscription does not enroll new events; already-enrolled events remain stored and are blocked while paused.

### 13.3 Event and delivery identity

The immutable `PaymentEvent.eventId` identifies the original payment fact. Each enrolled subscription gets its own `deliveryId`. The stored body includes both IDs, the subscription ID, and a payment event snapshot. Reattempts preserve the same delivery ID and exact bytes while the timestamp/signature change for each HTTP attempt.

The receiver can safely return a success response after it has durably accepted that delivery ID. If it stores the event but its response is lost, the sender sees an unconfirmed attempt and repeats it. The receiver recognizes the same delivery/body rather than performing the side effect twice. This is **at-least-once transport with receiver-side idempotency**, not magical exactly-once networking.

### 13.4 Signature protocol and encrypted secret

The receiver gets one each of `Fincore-Webhook-Key-Id`, `Fincore-Webhook-Timestamp`, and `Fincore-Webhook-Signature`. The key ID is the subscription UUID. The signed input is:

```text
ASCII("fincore-merchant-webhook-v1\n" + keyId + "\n" + timestamp + "\n")
|| exact stored body bytes
```

`MerchantWebhookCrypto.signature` creates `v1=` plus lowercase HMAC-SHA256 hex. The sink checks the exact body and an inclusive ±300-second freshness window. A shared secret proves that the sender knows the signing key; it does not encrypt the payload or prove public-key nonrepudiation.

The 32-byte master key encrypts subscription secrets using AES-256-GCM. Authenticated additional data binds ciphertext to the master-key ID, merchant and subscription, so moving ciphertext between tenants/subscriptions fails authentication. One active master key ID is supported. Rotation is not implemented: changing the key ID makes old subscriptions unavailable until a deliberate re-encryption/rotation facility is designed. A production deployment should consider a managed KMS/HSM and explicit staged key rotation.

### 13.5 Transaction boundaries and delivery ordering

The V19 database trigger observes each inserted `payment_outbox` row and snapshots a delivery for every active subscription belonging to the payment owner. Because this happens in the source transaction, a committed payment event cannot be missing its enrolled notification, and a rolled-back event cannot leave an orphan delivery.

`MerchantWebhookRepository.candidate` claims one eligible delivery using PostgreSQL row locks and `SKIP LOCKED`. `MerchantWebhookTransactions.claimNext` writes an attempt and a fresh lease token in a short transaction. `MerchantWebhookDispatcher` then calls `MerchantWebhookHttpClient` after that transaction has ended. A second short transaction records the response only if the same token still owns the lease. Expired workers cannot overwrite a newer claim. Expired attempts are closed as `ABANDONED` before reclaiming.

For a given subscription and payment, a later payment version waits while any earlier delivery is not `DELIVERED`. A `DEAD` earlier delivery blocks successors until an operator retries it. This preserves merchant-visible order at the cost of per-payment head-of-line blocking; unrelated subscriptions/payments can progress concurrently.

### 13.6 Retry, failure, and manual recovery

Only a complete 2xx response acknowledges delivery. Non-2xx responses, timeouts, oversized acknowledgements, and connection failures remain unconfirmed. The immutable body and delivery ID are retained. Retries use a bounded cycle budget and exponential delay capped by configuration; exhaustion changes the delivery to `DEAD` and records the last error code/status.

`POST /api/v1/merchant/webhooks/deliveries/{deliveryId}/retry` requires the current retry generation, a unique request UUID, and a reason. The delivery returns to `PENDING` in the same transaction as an immutable retry audit. Exact retries replay the saved decision; changed intent conflicts. A manual retry starts a new cycle but does not erase older attempts.

An HTTP timeout does not mean the merchant failed to process the event: the receiver may have committed before its acknowledgement disappeared. Therefore retries always reuse the exact body and ID. The local sink records the delivery before sending 204 and returns a conflict for the same ID with different bytes.

### 13.7 Tenant and network boundaries

The management API lives under `/api/v1/merchant/webhooks` and uses the existing merchant Basic principal and CSRF rules. Repository queries scope IDs by authenticated merchant; a foreign subscription/payment/delivery is reported as not found. Metadata reads expose safe state, hashes, timestamps and attempts, excluding payloads, encrypted secrets, plaintext signing secrets and receiver response bodies. Creation and its exact accepted replay deliberately return the subscription signing secret, as described above.

For this local sandbox, `MerchantWebhookDestinationPolicy` accepts canonical literal `127.0.0.1` or `[::1]` HTTP(S) URLs and the worker uses no proxy and follows no redirects. Literal loopback avoids DNS rebinding at this boundary. A production callback service would need a separately reviewed egress-control design; simply allowing arbitrary hostnames would create SSRF and internal-network access risk.

### 13.8 Database-enforced evidence

`V19__add_reliable_merchant_webhooks.sql` adds subscriptions, immutable state-change audits, deliveries, attempts, and manual retry audits. The trigger captures the exact new outbox snapshot. Checks bind each stored SHA-256 to its body, enforce bounded body/counter/state shapes, and deferred commit checks require delivery counters and latest attempt state to agree. Immutable identity columns and audit rows reject ordinary update/delete; lease and terminal transitions are constrained in PostgreSQL as well as Java.

Validation migrated both an empty schema through V1–V19 and a separate clone from V18 to V19. The upgrade preserved its preexisting payment, outbox event and merchant counts; earlier migration files were not edited.

Application checks provide understandable API errors; database constraints protect the evidence even if a different application path writes SQL. They are not a defense against a superuser who can alter schema/functions, nor a substitute for backup and retention controls.

### 13.9 Alternatives and tradeoffs

| Chosen approach | Alternative and why it was not chosen here |
| --- | --- |
| Enroll from the PostgreSQL outbox insert trigger | A later application callback or poll could crash after the payment commits but before enrollment. A trigger makes enrollment part of the same database transaction, but couples the schema to the v1 event envelope. |
| Persist exact body, IDs, hashes and attempt history in PostgreSQL | Rebuilding bodies on every retry risks event drift; an in-memory queue loses work on restart. Durable rows cost storage and still require future retention/archival policy. |
| Network call outside a database transaction, with lease token fencing | Holding row locks during HTTP spends database connections on slow/unavailable merchants. The lease allows crash recovery; fencing prevents an old worker from recording over a later claim, at the cost of duplicate sends after uncertain outcomes. |
| At-least-once delivery plus stable ID and receiver deduplication | Exactly-once side effects across PostgreSQL and arbitrary HTTP receivers are not available as one atomic transaction. The receiver must deduplicate; a 2xx acknowledgement alone cannot prove its downstream work is exactly once. |
| HMAC per subscription, with AES-GCM encryption at rest | Plaintext storage exposes keys in database snapshots. A static global bearer secret has weak tenant isolation and body binding. KMS would improve key custody/rotation but adds external infrastructure beyond this local block. |
| Literal loopback allowlist, no proxy or redirect | Arbitrary hostnames/redirects are convenient but risk SSRF and policy bypass. The strict local restriction intentionally limits this milestone; production needs controlled egress, not a relaxed regex. |
| Bounded exponential retries, then audited `DEAD` plus explicit merchant retry | Infinite retries can monopolize resources and hide a permanently broken receiver. Automatic forever-retry is simpler operationally but removes an explicit recovery decision and bounded workload. |
| Preserve per-payment delivery order | Unordered parallel events improve throughput but let a merchant observe `CAPTURED` before `AUTHORIZED`. Ordering can head-of-line block one payment; unrelated payment streams remain concurrent. |

### 13.10 Main code path

```text
PaymentService transition
→ payment_outbox INSERT
→ V19 enroll_merchant_webhooks trigger
→ MerchantWebhookRepository.candidate
→ MerchantWebhookTransactions.claimNext (short DB transaction)
→ MerchantWebhookDispatcher.dispatchBatch
→ MerchantWebhookHttpClient.send (no DB transaction)
→ MerchantWebhookTransactions.finish (lease-token check, short DB transaction)
→ merchant delivery + attempt history
```

## 14 Milestone 20 Partial operations and explicit fee accounting

Repeated local captures and refunds require amounts and operation-level journals in addition to status. This chapter completes the implemented scope of this volume.

### 14.1 Why this changes the financial model

The earlier ledger represented one full capture and one full reversal per payment. That model cannot describe two shipments charged separately, several customer refunds, or an explicit processing fee. A single status and the original payment amount no longer describe the complete financial position.

Milestone 20 introduces an amount-command mode for **manually authorized local payments before their first capture**. The first accepted capture permanently enrolls that payment in this mode. The existing full-payment API and provider integration remain available for payments using those workflows. A payment cannot mix their journals with amount commands.

The new API models local accounting decisions. It does not send partial commands to the HTTP provider: that protocol and its verified callbacks still support full authorization/capture/refund amounts. There is no live money movement, remote authorization release, or bank settlement implied by these local entries.

### 14.2 Concrete policy implemented in this project

| Rule | Behavior |
| --- | --- |
| Authorization ceiling | The payment's original `amount` is immutable; total captures cannot exceed it. |
| Partial captures | More than one capture is allowed while capture capacity remains and no refund has happened. |
| Refund identity | Each refund names a capture operation from the same merchant/payment. |
| Refund ceiling | Total refunds against a capture cannot exceed that capture's principal. |
| Capture closure | The first refund closes all remaining capture capacity on the payment. |
| Full refund | Refunding all captured principal makes the payment REFUNDED, even if some original authorization was unused. |
| Fees | Each capture explicitly supplies a fixed fee from zero through its principal amount. |
| Refund fee policy | Refund commands require zero fee. Original capture fees are retained; no fee refund or proration is performed. |
| Work bound | At most 100 accepted amount commands per payment, including refunds. |
| Money | Positive principal, nonnegative fee, exact two-decimal NUMERIC(19,2), one immutable payment currency. |

These are deliberate sandbox business rules. Other products can choose different fee refund and capture-closure policies; changing them requires new rules and verification rather than an implicit arithmetic tweak. Fees are supplied as explicit command intent, not calculated from a production merchant tariff.

### 14.3 The totals and status now mean different things

`PaymentResponse.amount` and the v1 payment-event snapshot continue to mean the original authorization amount. The new owned GET `/api/v1/payments/{paymentId}/accounting` returns:

```text
capturedAmount       = sum of capture principal
refundedAmount       = sum of refund principal
feeAmount            = sum of capture fees
refundableAmount     = capturedAmount - refundedAmount
merchantPayable      = capturedAmount - refundedAmount - feeAmount
remainingCaptureAmount = authorization - capturedAmount, only while capture is open
```

`PARTIAL_CAPTURE` means captured principal is positive but below authorization, with no refunds. `CAPTURED` means authorization has been captured in full. `PARTIAL_REFUND` means some captured principal has been refunded. `REFUNDED` means all captured principal has been refunded. Payment version advances once for every accepted command, including two consecutive operations that retain the same lifecycle status.

The accounting read obtains one Repeatable Read observation. Current totals come from committed journal headers. An accepted-request replay reconstructs the historical accounting snapshot from the immutable command prefix ending at that request's source version. A late replay therefore returns its original result rather than today's larger totals.

### 14.4 Worked accounting example

Authorize INR 100, capture 60 with a fee of 3, then capture 40 with a fee of 2:

| Journal | Debit | Credit |
| --- | --- | --- |
| First capture principal, 60 | PROCESSOR_CLEARING 60 | MERCHANT_PAYABLE 60 |
| First capture fee, 3 | MERCHANT_PAYABLE 3 | PLATFORM_FEE_REVENUE 3 |
| Second capture principal, 40 | PROCESSOR_CLEARING 40 | MERCHANT_PAYABLE 40 |
| Second capture fee, 2 | MERCHANT_PAYABLE 2 | PLATFORM_FEE_REVENUE 2 |

Clearing is 100, merchant payable is 95, and platform fee revenue is 5. Each journal has two equal opposing entries in one currency. Fees are distinct journals linked to the same amount operation as their principal, preserving gross capture visibility.

Refund all 60 against the first capture and all 40 against the second. Each refund debits merchant payable and credits clearing; its journal names the original principal journal. Clearing becomes zero, fee revenue stays 5, and merchant payable becomes **-5**. This negative amount is visible rather than clamped: under the chosen retained-fee policy, the merchant owes the fee after all principal has been returned. There is no automatic collection, credit-limit facility, bank movement or completed settlement in this milestone.

### 14.5 Why a request UUID and expected version are both required

`requestId` identifies one immutable financial intent. An exact accepted repeat returns the same operation and historical accounting snapshot with `replayed=true`, even after later captures/refunds. Reusing that UUID for a different payment, amount, fee, action, capture reference or source version conflicts.

`expectedVersion` states which payment observation the merchant intended to change. It is an explicit JSON integer; omission, null, strings and fractional values are rejected. An unaccepted command using an outdated version returns a conflict. The caller first resolves a possibly lost response using the original command; changing version and UUID creates a different financial intent and must be deliberate.

Numerically equal inputs such as `40`, `40.00` and `40.000` normalize to the same exact two-decimal intent. Nonzero excess decimal places are refused. Precision and scale are checked before rescaling so extreme decimal exponents do not trigger huge allocations. Java's decimal comparison and representation rules explain why numeric equality must be distinguished from scale-sensitive object equality. [Java 21 BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html).

### 14.6 Concurrency and transaction ownership

`PaymentAccountingService.submit` owns a new Read Committed transaction. It derives the merchant from `MerchantContext`, locks the owned source payment with `FOR UPDATE NOWAIT`, then checks prior request identity and current capacity. The lock serializes all capture/refund decisions for that payment. The expected version also rejects a stale new intent, while JPA's existing optimistic version update remains in place.

Consecutive partial commands can keep the same status. `Payment.applyAmounts` therefore advances `updatedAt` by at least one microsecond, even if the wall clock repeats or moves backward, ensuring the managed entity is dirty and Hibernate advances its version. A clock-regression test verifies that behavior.

Two concurrent INR 70 captures against INR 100 cannot both reserve the same capacity. One wins; the other sees contention or a changed version. The same applies to two refunds against one capture. NOWAIT provides a prompt conflict instead of waiting while holding arbitrary caller resources. PostgreSQL describes how these row locks interact with competing updates. [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html).

Inside the transaction, the service inserts the immutable accepted command, changes and flushes the payment, appends principal/optional fee journals, and records the outbox event. The existing M19 enrollment trigger creates merchant notification intent in that transaction. A late outbox or missing-journal failure rolls all of it back. No HTTP call is performed in this path.

### 14.7 Database defenses beyond Java checks

`payment_amount_bindings` records the first source version and command for the payment's permanent amount mode. `payment_amount_operations` stores tenant/request identity, amount, fee, source version, capture reference, resulting status, principal/fee journal IDs, transaction identity and creation time. Both tables reject ordinary UPDATE/DELETE.

V20 extends `ledger_transactions` with `amount_operation_id`. Legacy capture/refund uniqueness remains enforced by partial indexes. Amount journals are unique by command and journal kind. Multiple amount refunds may reference one principal journal, while the operation-insert guard enforces the remaining principal under the payment row lock. The original immutable ledger-entry and balance checks remain.

Deferred checks require the final payment version/status, command count and amount totals to agree, both named journals to exist and match their command, and the outbox event to represent the same resulting payment version. They inspect the completed transaction, which allows the command and journals to reference each other through deferred foreign keys. PostgreSQL supports deferring constraint-trigger execution until transaction end. [PostgreSQL CREATE TRIGGER](https://www.postgresql.org/docs/17/sql-createtrigger.html).

These rules prevent a supported SQL writer from committing half a financial operation. They do not protect against an administrator who can disable/replace schema guards. V1–V19 files and existing rows are preserved; the V19→V20 test checks a prior payment before-image and empty initial amount enrollment.

### 14.8 APIs and actual code flow

| Endpoint | Result |
| --- | --- |
| POST `/api/v1/payments/{paymentId}/amount-operations` | Accept or replay CAPTURE/REFUND amount intent. |
| GET `/api/v1/payments/{paymentId}/amount-operations` | Up to 100 immutable operations in source-version order. |
| GET `/api/v1/payments/{paymentId}/accounting` | Current principal/fee totals, capacity, status, version and mode. |

They use the existing merchant Basic principal and CSRF protection. Missing and foreign payment IDs return 404. Capture references are resolved within the authenticated merchant and payment. Existing ledger and merchant balance reads include the new journals/account, and ledger responses include `amountOperationId` for grouping.

```text
PaymentAccountingController.submit
→ PaymentAccountingService.submit
→ PaymentAccountingRepository.lock + accepted-request lookup
→ binding/command insert
→ Payment.applyAmounts + PaymentRepository.flush
→ LedgerTransaction.amountPosting + LedgerRepository.save
→ OutboxService.record → merchant delivery enrollment
→ PostgreSQL deferred commit checks
→ PaymentAccounting.Result
```

### 14.9 Event, projection and provider compatibility

The event JSON shape and schema version remain v1, with two new lifecycle enum values. Consumers, projections and the local merchant sink recognize these values; the newest payment version wins even if older events arrive later. The payload's `amount` remains authorization, so a notification signals a new lifecycle snapshot and the merchant reads accounting/history for partial amounts and fees. The event does not become a complete accounting report.

Deploy the new application and consumers together before emitting these enum values. Older enum readers can reject them; mixed-version interoperability is not claimed. Earlier event bytes, provider intent/result protocols and incoming callback verification are unchanged. Full provider-managed payments cannot enter local amount mode, and amount-managed payments cannot use the legacy full capture/refund/fail endpoints.

The headless event-operations CLI also explicitly disables both merchant-webhook switches, preserving operator commands when inherited API webhook credentials are invalid or scheduling is enabled.

### 14.10 Alternatives and tradeoffs

| Chosen approach | Alternative and why it was not chosen here |
| --- | --- |
| Separate principal and fee journals | A three-leg capture is valid accounting but requires a new journal-shape contract. Two-leg journals preserve the established invariant and show gross principal and retained fee separately, at the cost of more rows. |
| Immutable commands plus journal-derived current totals | Mutable captured/refunded/fee counters can make reads cheaper but introduce another value that must be kept consistent. The 100-command limit makes exact aggregation practical here. |
| Refund names one capture | A payment-wide refund pool is easier for callers but hides which charge is being reversed. Explicit allocation prevents one capture consuming another's remaining principal; multi-capture refunds require multiple commands. |
| Fixed explicit retained capture fee | Percentage fees require a tariff, rounding, allocation and fee-refund policy. Those choices are not invented in this block; callers supply an exact approved sandbox fee. |
| Close capture at first refund | Interleaving new captures after refunds is possible but needs an authorization-release/reuse contract. The chosen closure gives a small, testable state machine. |
| New transaction, source row lock and expected version | Optimistic versioning alone can protect a flush, but decisions also read aggregate capacity across command rows. Serializing that aggregate boundary simplifies correctness; contention is returned promptly. |
| Add a permanent amount-mode binding | Silently converting an existing provider or legacy full journal would invent unsupported capture allocation. Enrollment before first local capture keeps prior history interpretable. |
| Extend lifecycle enums while retaining the snapshot shape | A new full event schema could carry accounting totals but would require coordinated versioned consumer/rebuild semantics. This block exposes full totals through the accounting API and explicitly requires enum-aware deployment. |

### 14.11 Failure cases to understand

- Response lost after commit: repeat the same command to recover its accepted result; no duplicate journals or event.
- New request with an old version: conflict; observe current state before deciding on another intent.
- Over-capture/over-refund: refused before posting; PostgreSQL independently checks the limits.
- Fee or outbox write fails: payment, enrollment, command, journals and event all roll back.
- Full principal refund with retained fees: negative merchant payable is reported and remains unresolved debt for future settlement policy.
- 100 commands reached: no further amount operations are accepted, including refunds. The cap is a teaching workload bound and would require a carefully designed extension before production use; do not deploy it as a customer-facing refund restriction.
- Provider-bound payment: amount API refuses it; complete the established full provider workflow.

## 15 Why the recent developments matter together

### Correct effects need ownership and evidence

The early platform answered whether a local transition and its journal could commit correctly. M16 makes that question tenant-specific: the caller must own the payment, retry key, journal read, provider command and notification. A valid payment UUID alone confers no authority. Deriving merchant identity from a typed authenticated principal prevents a caller from selecting another merchant in a JSON field.

M17 and M18 add evidence from another process. An approved remote outcome can be applied only to its frozen command and matching source state. Authentication of a callback does not make every payload appropriate for every payment. Identity, instance, amount, currency, expected state and terminal-history checks remain necessary after the signature passes. Authentication establishes who may speak; domain rules determine what that message may change.

M19 makes the result observable to merchants without weakening financial atomicity. The durable delivery is created with the outbox event, but sending it is subsequent work. A failed receiver can delay notifications without undoing a capture. A merchant can inspect delivery evidence rather than guessing whether a missing notification means a missing payment.

### More operations require richer accounting

M20 exposes a distinction that full-amount examples hid: authorization, captured principal, refunded principal, fee revenue and payable are separate quantities. A payment status is a lifecycle summary. It cannot tell a merchant which shipment was charged, which capture was refunded, or why the net payable differs from gross principal. Immutable operation identity and journal links make those explanations possible.

The change also reaches older components. Consecutive partial operations may preserve the same status but require a new version and event. Enum-aware consumers and rebuild logic must recognize the new states. Merchant notification bodies retain the original authorization amount, so accounting readers must use the explicit accounting API. A change in the financial model therefore requires attention to versioning, projection behavior, notification meaning and deployment compatibility.

### Operational evidence protects interpretation

M11–15 answer a different set of questions: which view was maintained, why was it replaced, what target artifacts remain, which exact bodies were copied, who reviewed that copy, and is that approval still current? Without permanent identities and immutable records, old observations can be misread after replacement or configuration changes.

This evidence does not establish that every historical event ever existed or was retained. Matching latest source and projection states is useful but narrower than complete historical coverage. A full preservation bundle contains the artifacts named by its plan, not all financial history. A successful offline check says the supplied bytes match trusted references; it cannot prove an external storage system will retain them tomorrow.

### The common pattern across the project

The recurring design is: define immutable intent, give it a scoped identity, make the authoritative local change atomic, keep network work outside that transaction, and preserve enough evidence for exact retry and diagnosis. New intent is checked against current state. Accepted intent is recovered without silently reapplying it. Reports describe their observation boundary. Work is admitted under explicit limits.

The pattern has costs. It creates more records, migrations and service boundaries than a single status update. Storage grows because receipts, commands, journals, attempts and audits are retained. Leases require clocks and recovery rules. Bounded refusals require callers and operators to respond deliberately. Those costs buy understandable behavior under interruption; they do not remove the need for capacity planning and operational practice.

## 16 Failure and recovery playbook

This chapter is a reasoning checklist. Use the linked runbooks for exact commands. A recovery action must preserve the original identity when the question is whether an earlier request committed. Do not invent a new request simply to bypass uncertainty.

### Lost accepted operator response

An operator submits target retirement, the transaction commits and the response disappears. Later another target is enrolled. Retrying the original UUID, actor and body returns the original decision; it cannot retire the new target. A different reason or target under the same UUID conflicts. The operator reads current route state separately. Historical request replay protects the command, while a route revision protects a new decision against concurrent change.

### Same count but changed evidence

A plan records five artifacts. A retained body is altered in a privileged maintenance scenario, leaving five rows. A count-only review would miss the change. The plan's sorted identity/digest manifest detects a changed representation and marks fresh review stale. The operator creates new intent after investigating; an exact retry of the old plan still retrieves the originally accepted plan. Hashes detect disagreement with trusted references and do not explain or repair the changed row.

### Valid copy but expired or withdrawn approval

A copy verifies correctly but the approval expired or a revocation committed. The old metadata remains an accurate acceptance record. Fresh server review reports the lifecycle blocker. Replaying the acceptance does not renew it, and offline verification cannot observe the server's current revocations. A new approval must pass the current checks. Even a current review continues to report no deletion permission.

### Cross merchant request

Merchant A submits an ID belonging to merchant B. The repository lookup includes merchant ownership and returns not found, matching the missing-ID behavior. The response does not reveal B's state. Reusing a creation key in A's scope is independent of B's key. An operator credential or a role-shaped string is not a substitute for a typed merchant principal on the merchant route.

### Remote approval with no usable response

The sandbox commits approval and the connection fails. The command becomes UNKNOWN while local financial state remains unchanged. The safe actions are an exact retry of the existing command or an authenticated callback for that command. A new provider key risks a second remote operation. A late worker must still pass the current lease token before recording completion; provider idempotency protects the remote effect when both workers sent the request.

### Authentic but inapplicable callback

A callback can have a valid MAC yet name an unknown command, a READY command that was never dispatched, or a result inconsistent with terminal history. The service records a diagnostic disposition rather than inventing authorization. The immutable receipt is final for that event identity; it is not a queue that automatically retries application after unrelated state changes. A 200 acknowledgement means the receipt was durably handled, not that the payment was approved.

For a previously accepted event ID with changed bytes, the receiver returns conflict. An exact accepted repeat returns its recorded result without a repeated journal. A late matching result for an already-completed command records agreement without rewinding a later capture or refund. If local application fails before commit, receipt and effects roll back together, so a retry has a consistent starting point.

### Merchant receiver commits and its response disappears

The delivery remains unconfirmed at the sender. A new attempt uses the same delivery ID and body with fresh timestamp/signature headers. The receiver recognizes its durable prior acceptance and acknowledges without repeating its business effect. When the sender lease expires, a later worker can reclaim; the older worker cannot finish over the new token. This guarantees neither a single physical send nor the receiver's downstream exactly-once behavior.

### Dead delivery blocks a later version

For one subscription/payment, a DEAD earlier delivery blocks newer versions. Inspect the destination and attempt metadata, repair the local receiver, then request a manual retry with its current retry generation, a new request UUID and a reason. The new cycle retains prior attempts and the original delivery body. Retrying a changed payload under the old delivery ID would violate the receiver contract. Other payment streams have separate ordering boundaries; this is not a throughput or fairness guarantee under every workload.

### Two captures compete for the same capacity

Two commands each request 70 against authorization 100. The owned payment row lock serializes capacity decisions. One can commit; the other receives contention or a changed-version conflict. An exact accepted request is recovered before applying today's version/capacity checks. If the fee journal or event fails late, all local effects roll back, including mode enrollment and merchant delivery intent.

The 100-command lifetime cap includes refunds. It bounds the teaching implementation's work but can refuse a needed refund at the cap. This requires an explicit redesign before a customer-facing deployment. Do not weaken a constraint ad hoc or hide the refusal as a successful refund.

### Negative payable after retained fees

Returning all captured principal does not return the capture fees in this policy. Payable can become negative. The API exposes that position instead of clamping it to zero, which would erase an obligation. Future settlement must define how debt is collected or carried; the current code does not perform that collection, transfer funds or manufacture an offsetting journal.

## 17 Choosing approaches and understanding their costs

The milestone chapters give detailed comparisons. These project-wide decisions are useful to explain in a design discussion because they connect several implementation blocks.

### PostgreSQL as the local authority

The existing relational database can enforce scoped uniqueness, immutable relationships, row locks and commit-time checks across payments, journals and events. Keeping those facts together makes local atomicity concrete. Splitting them across independent services or data stores would require additional messaging, compensation and reconciliation contracts. That distribution can be justified by scale or organizational boundaries, but those benefits have not been demonstrated here.

The cost is database coupling: schema triggers know about allowed lifecycle and event shapes, migrations need upgrade tests, and broad projection routing fences can restrict concurrency. Database-first correctness also requires careful resource limits and trustworthy privilege management. A cache may help later risk/rate use cases; it should not become a second financial authority by accident.

### A bounded protocol before a live provider

The separate sandbox process allows real network uncertainty, independent commits and restart recovery without vendor credentials or live funds. It teaches the adapter boundary more honestly than an in-process mocked boolean result. A real provider SDK would supply a different protocol, not remove the need to understand idempotency retention, ambiguous outcomes, callback authentication and reconciliation.

The sandbox has deliberately narrow full-amount semantics and a file journal. Process-restart tests are useful evidence but do not establish sudden-power-loss durability or independent recovery. Retaining the provider instance UUID and its original journal directory together is essential. An empty directory under an old instance identity does not recover old idempotency decisions.

### Synchronous callback application at a local transaction boundary

M18 commits the receipt and applicable local effect before acknowledgement. A durable inbox plus asynchronous worker could decouple ingress latency and improve throughput. It would also need worker claims, retries, terminal handling and independent monitoring before an acknowledged inbox row could be considered safely processed. The current synchronous boundary is smaller to reason about, while callback latency and lock contention remain operational limitations.

### Shared secret authentication for the sandbox

HMAC binds exact body bytes and freshness to a known key. A static bearer token alone would not provide that body binding. Asymmetric signatures could allow separate verification keys and different trust properties, but require key distribution, signing policy and rotation rules. Shared-secret HMAC fits the one local provider protocol; it does not supply nonrepudiation or general vendor compatibility.

For merchant notifications, per-subscription secrets limit the credential scope. AES-GCM keeps those secrets encrypted in stored rows and binds their context, but the configured master key remains a sensitive dependency. A KMS/HSM and staged rotation are future operational choices. They would improve particular controls only after their availability, permissions and failure behavior are designed.

### Basic authentication before a full identity platform

Basic plus BCrypt, typed principals and repository scoping makes the local merchant boundary testable without an identity server. Authentication is checked per request. Browser-attached Basic credentials still justify CSRF protection, so an authentication policy described as stateless does not imply that a CSRF token cannot use a session.

OIDC/OAuth can centralize identity, token issuance and lifecycle, but they do not automatically implement payment ownership. The service would still need tenant scoping on every owned read/write. The current choice omits federation, MFA, user administration and general credential rotation. It requires TLS for a real network deployment and is not presented as the final identity architecture.

### Two leg journals and explicit fees

A three-leg capture can be valid double-entry accounting, but changing the established journal shape would expand its contract. M20 preserves two equal opposing entries per journal and groups separate principal/fee journals by one immutable operation. This makes gross activity visible and keeps existing checks useful, at the cost of additional rows and links.

Percentage tariffs, fee proration and fee refunds require rounding, allocation and reversal policies. The explicit fixed capture fee avoids inventing those business rules. Refunds name one capture so capacity is attributable. Closing capture after the first refund narrows the state machine; allowing later captures would require authorization reuse/release semantics that are currently absent.

### Review before irreversible cleanup

Age-based deletion would be simple to schedule but cannot alone explain deduplication consequences, missing artifacts, current approval or restore ability. The staged inventory/plan/copy/review approach makes those questions visible. Its immediate cost is storage retention and operator work without reclamation. Object storage, signatures and restore drills are complementary future controls, rather than alternative names for a same-database copy.

## 18 Verification evidence and its limits

### What the latest implementation run actually established

The latest broad Java 21 Maven `verify` completed on 26 October 2026 at 19:20:50 IST with BUILD SUCCESS. It executed 1094 checks with zero failures/errors. Docker-dependent checks were skipped, and the native Kafka broker suite was not selected. These are recorded implementation results, not tests rerun while creating this document.

| Suite in that broad run | Passed | Skipped |
| --- | --- | --- |
| Unit MVC service configuration and CLI | 662 | 0 |
| Real PostgreSQL | 421 | 0 |
| Packaged CLI | 11 | 0 |
| Docker dependent | 0 | 406 |

After the final monotonic timestamp fix, a focused Maven `verify` completed at 19:24:18 IST with 102 executed checks passing and zero failures/errors. It covered the domain and exact-money cases plus native accounting and legacy ledger suites against the final packaged source. These checks overlap the broad suite and must not be added to 1094 as distinct coverage. The broad run preceded that final fix; only the focused run tested the final timestamp change.

The initial broad attempt exposed eight old state-fixture cases that did not construct newly introduced partial states. The fixture was updated while retaining the original transition assertions; the subsequent broad run passed. This history is relevant to interpreting the final evidence, not an unresolved test failure.

### What the test layers contribute

Unit and service tests exercise validation, state transitions, exact monetary rules, protocol handling and orchestrator decisions. MVC tests exercise binding, authentication, CSRF, ownership and safe responses. Real PostgreSQL tests exercise actual locks, uniqueness, deferred commit failures, rollback and migration behavior that mocks cannot establish. Packaged CLI tests exercise startup routing and isolated command behavior outside an IDE.

M20's coverage includes split captures, capture-specific refund limits, retained fees, negative payable, historical accepted replay, changed/stale intent, real merchant credentials, concurrent capacity, late rollback, missing-fee commit refusal, immutable rows, workflow isolation, projection ordering, the exact 100-command boundary and the V19→V20 before-image. Earlier milestone summaries retain their own dated scopes; do not add their totals or assume every earlier environment was rerun at M20.

### Environment and practical limits

The recorded environment was Windows, Java 21 and PostgreSQL 18.6. Flyway warned that PostgreSQL 18 exceeds its tested support range. PostgreSQL 17.6 containers, Linux, load/capacity, public ingress, multi-instance or mixed-version deployment, live vendors/funds, partial provider operations and independent backup/restore remain unverified. A container skip is not a pass. Earlier native Kafka evidence is historical; the latest M20 broad run does not establish current broker integration coverage.

The isolated test PostgreSQL cluster was stopped cleanly after implementation verification, with no listener remaining on its 55432 port. The current documentation task did not start a payment server or Kafka broker. The test-only maintenance adjustment described in the M20 summary applied to the named disposable database and was not a change to application locking or a general deployment recommendation.

### How to verify this guide against the repository

Use [M20's summary](MILESTONE_20_SUMMARY.md) for the test accounting and environment. The ignored workspace logs are `.tools/milestone20-verify.log`, `.tools/milestone20-final-focused.log` and their QA/cleanup JSON files. Source guides and runbooks link the earlier implementation evidence. For this document, checks cover source links, code names, all ten chapters, current status, UTF-8, Word field updates and rendered layout. They do not replace an application regression run.

## 19 Current project status and next implementation

### Completed through Milestone 20

- Payment lifecycle, exact money, scoped creation idempotency and immutable balanced journals.
- Transactional outbox, Kafka delivery, receipt-based consumption and derived projection handling.
- Audited event recovery, quarantine/original replay, reconciliation, isolated rebuilds and controlled routing.
- Target replacement/retirement, retained-evidence inventory, bounded plans, verified copies and approval lifecycle.
- Merchant authentication, durable ownership and tenant-scoped operations and reads.
- Full-amount sandbox provider commands, uncertain-outcome recovery and verified incoming callbacks.
- Signed durable merchant notifications with ordered retry, attempt evidence and a local idempotent sink.
- Local partial captures, capture-specific refunds, retained explicit fees and operation-linked accounting.

### Currently possible in the local sandbox

A merchant can authenticate, create its own payment, complete the full sandbox provider workflow, recover an uncertain command, inspect its journals and receive signed notifications. Separately, a manually authorized payment can use local amount operations and accounting reads. An operator can diagnose event failures, reconcile and rebuild views, change routing under checks, inspect retired evidence, preserve it and verify a downloaded copy without starting Spring.

Configuration switches, valid credentials, the required processes and appropriate CSRF tokens still need to be supplied. “Implemented” does not mean those services are running now. The detailed local setup belongs to the runbooks and README.

### Not implemented yet

Settlement and independent sandbox reconciliation, Redis-backed risk/rate controls, a complete metrics/tracing/alerting deployment, independent restore/retention closure, and final deployment validation remain proposed. No live provider, bank payout, physical purge executor, restore importer or complete key rotation has been added. Provider partial commands, remote void/release/expiry, fee tariffs/refunds, FX and production debt collection are additional unresolved scope.

### Next proposed block is Milestone 21

The roadmap proposes settlement and external reconciliation. M20 can explain local clearing, principal, refunds, fees and payable. The next useful question is whether independently produced sandbox records agree with those expectations. This differs from projection reconciliation: a read model can match the local source perfectly while the external provider or settlement record disagrees with it.

A sensible design should define the external record identity and trust source, preserve imported evidence and accepted import identity, compare exact amounts/currencies/references, classify missing/duplicate/amount/fee/state discrepancies, and define controlled resolution. Those are proposed design questions for the next implementation, not existing endpoints or promised schema. The actual M21 contract must be designed and verified before documenting it as delivered.

| Proposed block | Purpose | Evidence needed before calling it complete |
| --- | --- | --- |
| 21 | Settlement and independent reconciliation | Independent records, repeatable comparison, explicit discrepancy and resolution rules |
| 22 | Redis risk and rate controls | Concrete decisions, concurrency/outage semantics, PostgreSQL financial authority retained |
| 23 | Observability | Useful metrics traces and alerts, bounded labels, security and failure demonstrations |
| 24 | Backup restore and operational closure | Independent trusted copies, demonstrated restoration, explicit retention and recovery contract |
| 25 | Deployment and final validation | Reproducible environment, end-to-end demo, failure load and security checks, final documentation |

Five proposed blocks remain under the bounded learning-project roadmap. Splitting them or adding business scope should be explicit. The finish line is a reproducible, explainable sandbox platform with demonstrated failure behavior and honest limits; milestone numbering cannot certify live-payment readiness.

## 20 Code API migration and runbook map

The paths below are relative to the independent `fincore-payments` repository. Classes live under `src/main/java/com/fincore/payments`. Migrations live under `src/main/resources/db/migration`. Use the source link or search the class name when reading from the Word edition.

### Milestones 11 through 15

| Area | Important files or classes | Role |
| --- | --- | --- |
| Routing lifecycle | ProjectionRoutingController ProjectionRoutingService ProjectionRoutingRepository | Checked promote rollback replace retire and current target ownership |
| Evidence review | ProjectionEvidenceController ProjectionEvidenceService ProjectionEvidenceRepository | Snapshot inventory and retirement review classification |
| Operating limits | ProjectionRoutingMetrics ProjectionOperationsProperties | Per-process routing outcomes and admitted comparison budget |
| Plan | ProjectionPurgePlanController ProjectionPurgePlanService ProjectionPurgePlanRepository | Immutable bounded manifest and fresh plan review |
| Preservation | ProjectionPreservationController ProjectionPreservationService ProjectionPreservationRepository | Exact bundle storage download and approval binding |
| Verification | ProjectionPreservationVerifier PreservationFileVerifierCli | Shared strict server and offline bundle checks |
| Approval lifecycle | ProjectionApprovalLifecycleController ProjectionApprovalLifecycleService ProjectionApprovalLifecycleRepository | Immutable attributed withdrawal and accepted-request replay |

Read the [routing runbook](PROJECTION_ROUTING_RUNBOOK.md), [evidence runbook](PROJECTION_EVIDENCE_RUNBOOK.md), [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md), [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md) and [approval lifecycle runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md) in that order. Existing [reconciliation](PROJECTION_RECONCILIATION_RUNBOOK.md) and [rebuild](SHADOW_PROJECTION_REBUILD_RUNBOOK.md) procedures supply the prerequisites.

### Milestones 16 through 20

| Area | Important files or classes | Role |
| --- | --- | --- |
| Merchant boundary | MerchantSecurityConfiguration MerchantContext MerchantPrincipal | Credential authentication typed identity and server-derived tenant scope |
| Provider commands | ProviderOperationController ProviderOperationService ProviderOperationTransactions | Commit intent claim network call and confirmed completion |
| Provider adapter | HttpSandboxPaymentProvider SandboxProviderServer SandboxProviderStore | Strict local HTTP protocol and independent durable decisions |
| Incoming results | ProviderWebhookController ProviderWebhookVerifier ProviderWebhookService ProviderWebhookRepository | Authenticate exact bytes and atomically record and apply known results |
| Outgoing notifications | MerchantWebhookController MerchantWebhookService MerchantWebhookRepository | Owned subscription controls deliveries and immutable manual recovery |
| Dispatch | MerchantWebhookTransactions MerchantWebhookDispatcher MerchantWebhookHttpClient | Leased short transactions around bounded HTTP delivery |
| Secrets and sink | MerchantWebhookCrypto MerchantWebhookDestinationPolicy MerchantWebhookSink | Context-bound encryption signing allowed destinations and durable receiver deduplication |
| Accounting | PaymentAccountingController PaymentAccountingService PaymentAccountingRepository | Owned immutable amount commands historical replay and current totals |
| Financial domain | Payment LedgerTransaction LedgerRepository OutboxService | Versioned state balanced operation-linked journals and source events |

Use the [merchant access runbook](MERCHANT_ACCESS_RUNBOOK.md), [provider integration runbook](PROVIDER_INTEGRATION_RUNBOOK.md), [incoming webhook runbook](PROVIDER_WEBHOOK_RUNBOOK.md), [merchant webhook runbook](MERCHANT_WEBHOOK_RUNBOOK.md) and [accounting runbook](PAYMENT_ACCOUNTING_RUNBOOK.md). The [event operations](EVENT_OPERATIONS_RUNBOOK.md) and [consumer recovery](CONSUMER_RECOVERY_RUNBOOK.md) procedures remain relevant when those new payment versions reach the internal event pipeline.

### Key API families

| Boundary | API or command | Meaning |
| --- | --- | --- |
| Operator | /api/v1/operations/projection-routing | Routing evidence lifecycle plans copies and approval operations |
| Merchant | GET /api/v1/merchant/me and /api/v1/merchant/csrf | Safe caller identity and CSRF token metadata |
| Merchant | /api/v1/payments/{paymentId}/provider-operations | Full provider command submit and owned progress |
| Machine | POST /api/v1/provider-webhooks/sandbox | Exact-path HMAC-authenticated provider result receiver |
| Merchant | /api/v1/merchant/webhooks | Subscription controls delivery metadata and explicit retry |
| Merchant | POST /api/v1/payments/{paymentId}/amount-operations | Local partial capture or capture-specific refund |
| Merchant | GET /api/v1/payments/{paymentId}/accounting | Journal-derived current totals and capacity |
| Offline | verify-preservation | Check exact bundle against trusted metadata and original plan |

This map names API families rather than substituting for each controller's exact request contract. For example, machine webhook authentication does not apply to merchant management, and the offline command has no server approval authority.

### Migration sequence

| Migration | Introduced schema responsibility |
| --- | --- |
| V11 | Target replacement retirement and departing route history |
| V12 | Retained-evidence review support and inventory access paths |
| V13 | Immutable bounded purge plans and artifact manifests |
| V14 | Exact preservation copies and attributed approvals |
| V15 | Approval validity and immutable revocations |
| V16 | Merchant identities payment ownership and scoped creation keys |
| V17 | Frozen provider bindings commands attempts and result guards |
| V18 | Exact incoming result receipts and completion evidence |
| V19 | Merchant subscriptions deliveries attempts and enrollment guards |
| V20 | Amount bindings commands operation journals and commit checks |

Read actual filenames and SQL before changing a schema; V12's operating budget and process metrics are application behavior rather than a persistent metrics store. Upgrade tests preserve prior rows and meaning. They do not establish that an older application binary supports new action enums, nullable historical fields or new insertion rules.

### Source and evidence references

The full source chapters remain available as [M11](MILESTONE_11_LEARNING_GUIDE.md), [M12](MILESTONE_12_LEARNING_GUIDE.md), [M13](MILESTONE_13_LEARNING_GUIDE.md), [M14](MILESTONE_14_LEARNING_GUIDE.md), [M15](MILESTONE_15_LEARNING_GUIDE.md), [M16](MILESTONE_16_LEARNING_GUIDE.md), [M17](MILESTONE_17_LEARNING_GUIDE.md), [M18](MILESTONE_18_LEARNING_GUIDE.md), [M19](MILESTONE_19_LEARNING_GUIDE.md) and [M20](MILESTONE_20_LEARNING_GUIDE.md). Each paired `MILESTONE_N_SUMMARY.md` records its own verification scope. [ROADMAP.md](ROADMAP.md) is the proposed completion boundary and [SESSION_LOG.md](SESSION_LOG.md) records implementation sessions.

This consolidated volume edits repeated introductions and centralizes status and future work. The authoritative implementation is the current code and migrations; historical milestone prose should not override a later implemented boundary. The Word edition and its Markdown source contain the same teaching chapters.

## 21 Study path glossary and learning handoff

### A practical reading sequence

First explain the local transaction from `PaymentAccountingController.submit` through `PaymentAccountingService.submit`, payment/journal/outbox writes and deferred checks. Then trace provider preparation, claim and completion around the network call. Compare a synchronous result with `ProviderWebhookService` applying an authenticated later result. Finally follow the enrolled merchant delivery through claim, send and token-checked finish.

For the operational track, trace target lifecycle, evidence inventory, plan, preservation and approval review. At every step state the exact identity, the observation or write boundary, and what the result does not authorize. This is more useful than memorizing annotations or status names without their role.

Use the tests while reading. A mock can show that a method was called; a PostgreSQL rollback/concurrency test shows whether the installed transaction boundary and schema guards behave as intended. Find one accepted replay test, one changed-intent conflict, one commit-time failure and one ownership refusal for each major command family.

### Exercises to explain to a teacher

1. Draw the moment at which the provider can have committed while PostgreSQL still has UNKNOWN. Explain why rolling back a local transaction cannot undo that remote decision.
2. Describe a race between a callback and synchronous finish. Identify the common financial path and the checks that stop a second journal.
3. Show which fields remain stable when a merchant delivery is retried after a lost acknowledgement, and which timestamp/signature fields change.
4. Work through authorization 100, captures 60 and 40, fees 3 and 2, then full principal refunds. Reconcile each account and explain the final negative payable.
5. Explain why a second partial capture needs a new version even if its lifecycle status is still PARTIAL_CAPTURE, including repeated or backward wall-clock observations.
6. Separate an exact accepted request retry from a new request carrying today's expected version. Explain why the former must not apply the action again.
7. Give an example where five retained rows still total five but no longer match a saved purge plan. Explain which fingerprint catches it.
8. Verify a downloaded preservation copy against independently trusted references, then describe why that says nothing about present approval revocation or restore success.
9. Compare a coherent Repeatable Read report with a protected new routing decision. Explain why the report cannot serve as a later write authorization.
10. Design the questions M21 must answer when local clearing agrees with the projection but disagrees with an independent settlement record. Keep proposed rules separate from implemented behavior.

These are study exercises, not extra claims of executed demonstrations. Use disposable local data for commands that change state and follow the runbook's configuration requirements.

### Compact glossary

| Term | Meaning in this project |
| --- | --- |
| Idempotent accepted replay | Recover the original accepted intent/result without another effect |
| Expected version or revision | Caller observation required for a new decision |
| Lease | Time-bounded local ownership of a worker attempt |
| Fencing token | Attempt identity checked before a worker may finish |
| Inbox or receipt | Durable evidence that one incoming event/body was handled |
| Transactional outbox | Event persisted with the source state rather than sent as a separate local write |
| At least once | A send may repeat and the receiver must handle duplicates |
| Derived projection | Latest read view produced from accepted events |
| Preservation manifest | Ordered named artifact identities and digests in a bounded plan |
| Approval review | Current snapshot of lifecycle and evidence blockers rather than deletion authority |
| Principal and fee | Gross capture/refund amount and separate retained processing charge |
| Merchant payable | Captured principal minus refunded principal minus retained capture fees |
| External reconciliation | Comparison against independently produced records rather than another local view |

### TOPICS TO LEARN FROM THIS SESSION

- Spring transaction propagation and proxy boundaries
- Read Committed and Repeatable Read observations
- Advisory locks row locks SKIP LOCKED and NOWAIT
- Deferred constraints immutable audit history and Flyway upgrade compatibility
- Read model lifecycle routing revisions and evidence retention
- Keyset pagination row budgets and metric cardinality
- SHA256 manifests exact UTF8 bytes and strict JSON validation
- Integrity authenticity durability and restoration
- Approval expiry revocation and historical accepted replay
- Authentication authorization tenant isolation Basic BCrypt and CSRF
- Distributed uncertainty stable idempotency keys leases and fencing
- HMAC body signing timestamp replay windows and security chains
- AES GCM associated data key custody and rotation
- Transactional outbox durable webhook enrollment and receiver deduplication
- Ordered delivery exponential backoff retry generations and dead letter recovery
- BigDecimal exact monetary scale partial captures and capture specific refunds
- Double entry journals fee revenue retained fees and negative payable
- Aggregate invariants expected versions and historical accounting snapshots
- Event compatibility and coordinated consumer deployment
- Real database concurrency rollback migration and packaged CLI testing
- Settlement and independent reconciliation
