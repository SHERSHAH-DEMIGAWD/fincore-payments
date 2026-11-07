# Milestone 11 learning guide — Controlled projection target replacement and retirement

**Current continuation (2026-10-24):** [Milestone 13](MILESTONE_13_LEARNING_GUIDE.md) adds audited bounded purge plans and fresh review. Plans retain all data and grant no deletion permission; see the [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). Earlier milestone descriptions below retain their historical scope.

Implemented 2026-10-23 in the independent `fincore-payments` project. Read after the [Milestones 1–10 guide](COMPLETE_LEARNING_GUIDE.md). The [routing runbook](PROJECTION_ROUTING_RUNBOOK.md) contains the commands; the [summary](MILESTONE_11_SUMMARY.md) records verification.

**Current continuation:** [Milestone 12](MILESTONE_12_LEARNING_GUIDE.md) now implements the evidence-review and operating-limits block proposed at the end of this milestone. Physical deletion remains unimplemented.

## 1. The problem this milestone solves

Milestone 9 can build several isolated views. Milestone 10 can enroll one of them, maintain it beside the baseline, and switch readers between those two views. Its lifetime restriction leaves a practical question: how can another reviewed build take over, or how can an unused warm target stop receiving updates?

Milestone 11 changes the restriction to **one maintained shadow at a time**. Replacement atomically enrolls a fresh candidate, switches reads to it and retires the old shadow. Explicit retirement removes the warm shadow while baseline reads continue. Both operations require the baseline to be active beforehand, fresh agreement and no tracked open gaps.

This is a local PostgreSQL read-model lifecycle. It does not move funds, replay financial operations, certify missing historical events, repair a damaged baseline, or deploy a new service.

## 2. Three lifecycle states and separate kinds of ownership

| State | Meaning in this project | Allowed next work |
| --- | --- | --- |
| `ISOLATED` | Run has never been enrolled; its own manifest/checkpoint workflow owns its target. | Step, catch up, validate, then checked enrollment. |
| `MAINTAINED` | Current route's shadow; normal consumption maintains it alongside baseline even while baseline serves reads. | Promote/rollback; replace or retire only after baseline rollback. |
| `RETIRED` | Run was enrolled previously and no longer owns live maintenance. | Inspect retained state and replay accepted historical commands; no new enrollment or rebuild mutation. |

`projection_rebuild_runs.routing_enrolled` now means **ever enrolled**. It never resets. `projection_routes.shadow_run_id` identifies the currently maintained shadow and can become null after retirement. `active_consumer` independently selects which view serves reads. Route revisions advance across every decision, including periods with no warm shadow.

`ProjectionRoutingService.target` exposes these states through authenticated `GET /api/v1/operations/projection-routing/targets/{runId}`. Its fresh read-only Repeatable Read transaction observes run metadata and current maintenance ownership together. `ProjectionRebuildService.validate` returns `RETIRED_EVIDENCE_RETAINED` for retired targets; comparison against their historical manifests can still report source drift.

**Why separate these identities?** A frozen build generation is historical evidence. A maintenance pointer names the target receiving future events. A read pointer names the target answering readers. None of those alone establishes current readiness.

## 3. Replacement is a fresh decision with explicit preconditions

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

## 4. Retirement releases maintenance while retaining evidence

`ProjectionRetirementCommand` carries request UUID, target run UUID, expected revision and reason. `ProjectionRoutingService.retire` uses the same authentication, fresh transaction, fence and data/gap checks.

Retirement requires baseline reads and the exact currently maintained run. It commits a `RETIRE` audit and advances the route revision while setting `shadow_run_id` to null. `PaymentEventConsumerRepository.maintainedShadow` then returns empty, so normal consumption applies only to baseline. Projection GET continues using its single SQL route/data lookup.

**Retirement retains** run identity, the irreversible enrollment flag, all manifest generations/items, accepted request decisions, receipts and the final projection rows. Database triggers continue freezing ever-enrolled build metadata after retirement. A fresh run can later be enrolled through `/promote` at the current revision; the retired identity cannot return.

This policy deliberately makes retirement different from deletion. No retention duration, physical purge, cancellation of an isolated build or automatic cleanup job was added. Storage still grows and needs a separate, reviewed retention policy.

**Failure example:** retire A at revision 3; later enroll B at revision 4. An exact retry of A's retirement returns its original revision/time and `replayed=true`, without retiring B. An exact retry of A's original promotion also returns its historical result without making A active again. Read current status separately.

## 5. Atomic handover and evidence protection

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

## 6. Why these choices fit and what alternatives would change

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

## 7. Holistic impact and the next implementation

The sequence now goes from **correct financial facts → durable delivery → explicit gaps → comparison → isolated rebuild → checked routing → repeatable target lifecycle**. We can replace the read model repeatedly while normal financial operations retain their original transaction/ledger boundaries. The permanent baseline still owns logical consumer diagnostics and recovery; retirement does not create a new failure namespace.

The next proposed block is **retention planning and observable operating limits**: define which retired artifacts and request/deduplication evidence must survive, add bounded inspection/metrics for storage and handover cost, and specify a safe purge workflow before deleting anything. Production-volume comparisons, contention behavior, multi-broker availability and deployment security need separate validation. Merchant/tenant access control and provider/settlement integration remain later business work.

## TOPICS TO LEARN FROM THIS SESSION

- Read-model lifecycle and separate read/maintenance ownership
- Permanent identities and evidence retention
- Atomic handover and bounded fanout
- Revision and target compare-and-set
- Idempotent operator requests versus new intent
- Shared/exclusive advisory locks and nonwaiting table locks
- Read Committed, Repeatable Read and transaction propagation
- Deferred constraints and immutable transition history
- Forward-only Flyway migrations and upgrade testing
- Authenticated principals, role authorization and CSRF
- Concurrency, rollback and real Kafka integration tests
