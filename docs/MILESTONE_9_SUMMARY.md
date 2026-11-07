# Milestone 9 — Isolated shadow projection rebuild and catch-up generations

Completed 2026-10-22 in **fincore-payments — Distributed Payment Processing Platform**.

## 1. WHAT WAS IMPLEMENTED

- Local CLI: `rebuild-create`, `rebuild-step`, `rebuild-status`, `rebuild-validate` and `rebuild-catch-up`.
- Generated shadow consumer identity and frozen current-source manifest per rebuild generation.
- Missing/invalid/disputed source inputs remain explicit blocked items with preserved evidence.
- Bounded resumable batches commit shadow receipts/projections and durable checkpoints together.
- Audited catch-up generations preserve prior manifests and support exact accepted-command replay.
- Read-only validation compares frozen inputs, shadow artifacts, current source changes and live recovery gaps.
- Immutable metadata, generation preconditions and database consistency guards protect progress.

## 2. CONCEPTS / TECHNOLOGIES USED

- Shadow logical identities — isolate rebuilding from normal consumer deduplication.
- Materialized manifests / generations — freeze reproducible inputs and retain catch-up decisions.
- Atomic checkpoints / row locks — safe batch resumption and concurrent worker ownership.
- TransactionTemplate / complete-transaction retry — bounded Repeatable Read planning retries.
- Request identity / generation CAS — exact replay and stale operator intent checks.
- Flyway V9 / deferred constraints — immutable evidence and enforced data/progress agreement.

## 3. IMPORTANT FILES

- `ProjectionRebuildService.java` — planning, bounded steps, catch-up and validation transaction boundaries.
- `ProjectionRebuildRepository.java` — manifests, generation identity, checkpoints and comparison queries.
- `ShadowRebuildPlanResult.java` / `ShadowRebuildView.java` — stable accepted plan result versus current progress.
- `ShadowRebuildStepResult.java` / `ShadowRebuildValidation.java` — batch and validation metadata.
- `V9__add_shadow_projection_rebuilds.sql` — runs, generations, immutable inputs and consistency triggers.
- `EventOperationsCli.java` — validated headless rebuild commands.
- `ProjectionRebuildDatabaseTests.java` — real transaction, fault, race, scope and isolation checks.
- `SHADOW_PROJECTION_REBUILD_RUNBOOK.md` / `MILESTONE_9_LEARNING_GUIDE.md` — commands, concepts, impacts and alternatives; master guide/README/handoff updated.

## 4. REQUEST FLOW

```text
EventOperationsCli rebuild-step
→ ProjectionRebuildService.step
→ ProjectionRebuildRepository.findRun / items
→ PaymentEventRecordDecoder.decode
→ PaymentEventConsumerRepository.insertReceipt / applyProjection
→ ProjectionRebuildRepository.markApplied / finishStep
→ shadow data + durable checkpoint COMMIT
```

## 5. IMPORTANT DESIGN DECISIONS

- Each run owns a generated target; live projection routing and financial state are preserved.
- Only current retained snapshots are rebuilt; missing historical facts are never synthesized.
- Exact create/catch-up replay returns its original plan result; steps continue remaining approved work.
- Catch-up appends immutable generations instead of rewriting earlier manifests.
- Checkpoints require matching receipts/projections and a consistent terminal generation state.
- BUILT and current-source agreement are separate observations; history stays NOT_CERTIFIED and promotion NOT_IMPLEMENTED.

## 6. TEST STATUS

- Full Maven `verify`: **BUILD SUCCESS**, **457 executed checks passed**, zero failures/errors.
- **269** unit/service/configuration/MVC/CLI checks; **176** real PostgreSQL invocations, including **40** rebuild cases.
- **7** real Kafka/PostgreSQL checks, including isolated rebuilding beside the live consumer; **5** packaged-CLI checks, including exact create replay, bounded shadow application and validation with an unreachable broker.
- Fresh V1–V9 migration/validation and earlier legacy upgrade regression passed. Fault/race cases covered planning and batch rollback, immutable evidence, concurrent workers/operators, blocked inputs, stale generations, shadow damage and terminal checkpoint consistency.
- The first targeted run passed 269 unit and 39 rebuild checks. Review then added the final-item/header guard and its regression test; the final full run used a fresh isolated database. V1–V8 migrations were preserved.
- **185 Docker-dependent methods skipped**. Compose/PostgreSQL 17.6 container execution, multi-broker failover and large-volume performance remain unverified. Actual local versions: PostgreSQL 18.6 and Kafka 3.9.1; Flyway warns PostgreSQL 18 exceeds its tested support.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ milestones 1–9: payment correctness through isolated shadow rebuilding and catch-up.

**CURRENTLY POSSIBLE:** ✓ construct and catch up an isolated latest-payment view while retaining frozen inputs and live incident obligations.

**NOT IMPLEMENTED YET:** ✗ live promotion/rollback execution, authenticated operator routing, automatic scheduling/cancellation/retention, arbitrary corrections or real provider/funds integration.

**NEXT LOGICAL MILESTONE:** controlled projection promotion/rollback with freshness fencing, reader-routing semantics and an authenticated operator boundary.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Shadow read models and logical consumer identities
- Materialized manifests and generations
- Atomic checkpoints and resumable batches
- Row locks and worker ownership
- Idempotent commands and generation CAS
- Repeatable Read serialization failures
- Complete-transaction retry and TransactionTemplate
- Canonical event contracts
- Latest-state rebuild versus historical replay
- Catch-up validation and promotion fencing
