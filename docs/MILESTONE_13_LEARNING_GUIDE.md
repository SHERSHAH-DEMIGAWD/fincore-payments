# Milestone 13 learning guide — Audited bounded purge planning

**Current continuation (2026-10-24):** [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md) adds verified full-body preservation exports and authenticated preservation-review approval. Deletion remains unavailable. See the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md); earlier milestone scope/next-work descriptions below are historical.

This continues the [Milestone 12 guide](MILESTONE_12_LEARNING_GUIDE.md) in the independent `fincore-payments` project. Read the [operator runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md) for requests and the [milestone summary](MILESTONE_13_SUMMARY.md) for verification results. Implementation date: 2026-10-24.

## 1. Why this milestone matters

The platform can repeatedly build, promote, replace and retire projection targets. Every retired target retains copied events, projections and build evidence. Milestone 12 made those retained copies visible and classified their age. An inventory screen alone cannot record a specific operator's intended cleanup scope or establish whether the same evidence still exists when someone returns later.

Milestone 13 records that intent as an immutable plan. It freezes one explicitly named retired target, its retirement decision, the route revision, the review policy, attributed reason and a bounded list of evidence identities and fingerprints. A fresh review compares this saved inventory with a new database snapshot.

This is preparation for a later retention workflow. **Every plan and review returns `deletionPermitted=false`.** No row is deleted, no immutability trigger is relaxed, no cleanup job runs, and no archive/export or approval is claimed. Even `CURRENT_REVIEW_ONLY` means only that the implemented freshness checks passed at that observation.

## 2. Where it fits in the whole project

| Milestones | Capability | Connection to this milestone |
| --- | --- | --- |
| 1–3 | Payment lifecycle, idempotent creation, immutable balanced ledger | Financial source records remain outside planning scope. |
| 4–5 | Transactional outbox, Kafka, deduplicated receipts and latest projections | Receipt identity has correctness value; deleting it casually could defeat deduplication. |
| 6–8 | Operations, tracked failures, controlled replay, reconciliation | Recovery evidence and historical coverage limitations remain intact. |
| 9 | Isolated builds and frozen catch-up generations | All retained generations and manifests belong to the target inventory. |
| 10–11 | Authenticated routing, replacement and retirement | An immutable departing decision establishes the target's retirement age. |
| 12 | Retention review, row budgets and local command measurements | The same review-age policy is reused; visibility now leads to durable planning intent. |
| 13 | Immutable bounded plans and fresh review | A future executor would need precise scope and preservation evidence before changing retention guards. |

The recent milestones develop the operational lifecycle around a derived read model. They do not add payment providers, move money, improve historical coverage to certified completeness, or complete production readiness. Counting milestones is not a reliable percentage of the eventual business platform.

## 3. A plan names one target explicitly

`ProjectionPurgePlanCommand` contains `requestId`, `runId`, `expectedRevision` and `reason`. The request UUID also identifies the saved plan. The actor comes from `Principal.getName()` in `ProjectionPurgePlanController.create`; a caller-supplied `actor` cannot replace it.

`ProjectionPurgePlanService.create` validates bounded single-line attribution, acquires the existing routing fence, checks accepted-request replay and then verifies current scope, exact route revision and retirement age. The source namespace and topic come from server configuration.

Only a target classified as `REVIEW_RETIRED_EVIDENCE` by the existing `ProjectionEvidenceService.reviewState` can receive a new plan. Isolated builds, currently maintained targets, missing/uncertain retirement evidence and targets inside the review window are refused. Baseline reads alone do not mean its shadow is retired: a rolled-back shadow is still maintained.

**Example:** A target retired at revision 3 is still inside the default 30-day review window tomorrow. Planning returns 409 and saves nothing. After the window, a new request with the current route revision can record its inventory. This threshold is the project's review policy, not a legal retention rule.

**Alternatives:** accepting any old run would confuse creation age with retirement age; accepting a rolled-back shadow would include a live target. An automatic scan of all old targets would make the operator's exact intended scope less explicit. A future multi-target workflow could be useful, but this implementation keeps a single target and an absolute evidence-row cap.

## 4. Bounded inventory means artifacts, not just targets

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

## 5. Fingerprints detect same-count changes

For each selected row, PostgreSQL converts `to_jsonb(row)::text` to UTF-8 and computes SHA-256. `SET LOCAL TIME ZONE 'UTC'` normalizes timestamp rendering for each inventory query. The plan stores only kind, scoped identity and hash. Payment payloads, merchant fields and source snapshots are not exported as artifact bodies. The plan does expose its own operator and reason for audit purposes.

**Example:** an external SQL writer changes a retired projection's merchant reference without changing its primary key or the number of rows. Counts still match, but its fingerprint changes. Fresh review reports `ARTIFACTS_CHANGED` and `STALE_REPLAN_REQUIRED`.

**Alternatives:** counts alone miss content changes. Storing all original rows in the plan would duplicate sensitive payloads and increase storage/response size. Hashing only primary keys would miss changes under the same identity. SHA-256 is available in the supported PostgreSQL versions without adding an extension or external service.

These hashes are comparison evidence, **not digital signatures, an archive, an authenticity certificate or a portable canonical event format**. They depend on this schema and PostgreSQL JSON serialization; schema or representation changes may conservatively stale a plan. The original evidence is still required for recovery and any future verified preservation process. A database owner who can bypass guards remains outside this application-level protection.

## 6. Immutable storage and transaction ownership

V13 adds `projection_purge_plans`. V1–V12 remain unchanged. The table stores version 1 planning metadata, the bounded artifact JSONB list and frozen preservation obligations. Foreign keys retain references to the selected generation and retirement audit revision.

`protect_projection_purge_plan` rejects updates and deletes. On insert it checks the enrolled run's scope/current generation, its actual departing RETIRE/REPLACE decision and timestamp, absence from current maintenance, current route revision and review-age constraint. Service validation also checks full current inventory and uses the shared age classification. These layers address different entry points; database constraints do not authenticate an HTTP principal or independently prove that every saved hash is genuine.

Creation owns a new **Read Committed** transaction and uses the existing exclusive, nonwaiting routing advisory fence and SHARE NOWAIT table freezes. Participating routing/consumer operations cannot change the evidence between checks and insertion. Busy work returns a conflict rather than waiting indefinitely. Transaction rollback removes an unsuccessful insert; the other application tables are untouched by successful planning too.

**Alternatives:** reusing a caller's old snapshot could accept stale intent. An unlocked read followed by an insert could race routing or target updates. Blocking locks could create long queues behind maintenance. The reused fence is coarse and briefly excludes unrelated application writes; its simplicity is acceptable for this bounded learning workflow. A finer design would need its own proven lock order and writer participation protocol.

The existing database ownership/privilege assumptions still apply. This is not protection against a superuser, disabled triggers, table truncation or arbitrary uncoordinated administrative SQL.

## 7. Exact retries recover the original accepted plan

After acquiring the fence, creation looks up `requestId` before checking today's route revision, policy window or artifact inventory. The same UUID must carry the same run, expected revision, actor, reason and configured scope. Exact replay returns the original saved plan with `replayed=true`. A changed intent under the same UUID returns 409.

**Example:** a plan was accepted at route revision 3, but the response was lost. Another target is then promoted at revision 4. Repeating the original request returns its revision-3 plan; it does not create another plan or make that old plan current. Calling `/review` reports `ROUTE_REVISION_CHANGED`.

The create response is reloaded from PostgreSQL after insertion so timestamp precision matches later retrieval/replay. It is returned only after the service proxy completes transaction commit.

**Alternatives:** rejecting every old expected revision before recognizing retries would make a lost response ambiguous. Generating a fresh plan UUID on every retry would duplicate audit intent. Returning an existing plan for any command with the same UUID could silently approve a changed reason, target or operator.

## 8. Fresh review is a consistent observation

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

This review checks the frozen target inventory and its recorded context. It does not rerun source/baseline reconciliation, certify historical coverage, assess every current recovery gap or verify preservation outside PostgreSQL. Growth of unrelated source payments alone does not change a retired target's inventory. A future approval/execution protocol must define its additional safety checks explicitly.

**Alternatives:** separate Read Committed queries could mix old metadata with new artifacts. Holding exclusive locks throughout a human review would obstruct application traffic. Treating a successful GET as approval would confuse an observation with authority. Any future executor must obtain its own fresh execution fence and conditions; a review response can become stale immediately after it returns.

## 9. Preservation obligations are explicit

The saved version-1 obligations require keeping run identities/routing decisions, preserving frozen manifests/checkpoints and target receipts/projections before any future purge, and keeping baseline/source/ledger/recovery evidence outside scope. Preserving routing history also protects exact historical request replay and permanent non-reenrollment of retired runs.

There is currently **no verified archive, preservation acknowledgement, two-person approval, execution token, deletion batch, scheduler or storage reclamation**. This milestone cannot discharge those obligations. The current target's original rows all remain in PostgreSQL, and the existing immutability protections continue to apply.

## 10. Actual request flow and important files

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

## 11. Verification and next implementation

See the [summary](MILESTONE_13_SUMMARY.md) for the final full-run totals and environmental limits. Database tests use a future observation clock to cross retention boundaries without rewriting immutable audit timestamps. They also cover same-count drift, the exact 1,000-row boundary and oversized rejection, no writes to other application tables, routing-fence contention, one read-only review snapshot, replacement retirement identity, immutable plans and V12 upgrade. MVC tests exercise authentication, roles, CSRF, trusted attribution, missing/invalid integral revisions and error responses.

**Next logical milestone:** verified preservation/export manifests and an explicit authenticated review/approval lifecycle that binds decisions to a fresh plan and preserved evidence. After that, a separately designed bounded executor could consider eligible copied data while retaining identities, replay semantics and required audit evidence. Physical deletion still needs failure/retry and post-purge correctness tests; it should not be added by merely disabling current triggers.

## TOPICS TO LEARN FROM THIS SESSION

- Retention policy versus deletion authority
- Immutable audit records and preservation dependencies
- Idempotent commands and exact request replay
- Compare-and-set revisions
- PostgreSQL JSONB and SHA-256 row fingerprints
- Bounded inventories and sentinel rows
- Read Committed versus Repeatable Read
- REQUIRES_NEW and transaction commit boundaries
- Advisory locks and nonwaiting table locks
- Spring Security principals, roles and CSRF
- Flyway migrations, constraints and triggers
- Concurrency, drift and migration integration testing
