# Milestone 11 — Controlled projection target replacement and retirement

Implemented 2026-10-23 in the independent `fincore-payments` project.

## 1. WHAT WAS IMPLEMENTED

- Authenticated replacement and retirement HTTP commands using the existing operator/CSRF boundary.
- Target inspection distinguishing isolated, maintained and retired runs.
- Fresh fenced checks of source coverage, old/new views, baseline agreement and tracked gaps.
- Atomic transfer to one new maintained shadow after explicit baseline rollback.
- Retirement releasing live maintenance while retaining all target artifacts.
- Exact historical request replay, permanent enrollment identity and frozen retired rebuild evidence.
- V11 migration preserving existing V10 routing decisions; operator and learning documentation.

## 2. CONCEPTS / TECHNOLOGIES USED

- Projection lifecycle and bounded fanout — one maintained shadow beside baseline.
- Revision/target CAS and request idempotency — stale intent and accepted retries.
- PostgreSQL advisory/table locks — fresh protected decision window.
- Spring transactions and Security — independent decisions and authenticated actors.
- Flyway and deferred triggers — compatible schema upgrade and durable transition history.

## 3. IMPORTANT FILES

- `ProjectionRoutingController.java` — replacement, retirement and target inspection endpoints.
- `ProjectionRoutingService.java` / `ProjectionRoutingRepository.java` — lifecycle checks, atomic decisions and audit replay.
- `PaymentEventConsumerRepository.java` / `ProjectionRebuildService.java` — current fanout selection and retired ownership reporting.
- `ProjectionReplacementCommand.java`, `ProjectionRetirementCommand.java`, `ProjectionTargetView.java` — lifecycle contracts.
- `V11__add_projection_target_lifecycle.sql` — route transitions, previous-target history and permanent freeze enforcement.
- `ProjectionRoutingDatabaseTests.java`, controller/security tests and `KafkaBrokerTests.java` — upgrade, race, rollback, security and live-delivery coverage.
- [Learning guide](MILESTONE_11_LEARNING_GUIDE.md) / [runbook](PROJECTION_ROUTING_RUNBOOK.md) — concepts, alternatives, impact and commands.

## 4. REQUEST FLOW

```text
POST /api/v1/operations/projection-routing/replace
→ ProjectionRoutingController.replace
→ ProjectionRoutingService.replace
→ ProjectionRoutingRepository.fence / switchRoute
→ PostgreSQL enrollment + immutable audit + routing COMMIT
→ PaymentEventConsumer.consume maintains baseline and the new shadow
```

## 5. IMPORTANT DESIGN DECISIONS

- Baseline must serve reads before replacement or retirement.
- Both lifecycle actions require fresh source/view agreement and no tracked open gaps.
- Exactly one current shadow receives fanout; old target identities are never reused.
- Retirement preserves data and history; physical purge is separate future work.
- Ever-enrolled metadata stays frozen while route revisions continue across target lifetimes.
- Existing V1–V10 migration files and historical response meanings are preserved.

## 6. TEST STATUS

- Final Java 21 Maven `verify`: **BUILD SUCCESS**, **573 executed checks passed**, zero failures/errors: 321 unit/MVC/service/configuration/CLI checks, 240 real PostgreSQL checks (including 64 routing cases), seven real Kafka/PostgreSQL checks and five packaged-CLI checks.
- Includes 25 added database cases, 14 added MVC/security invocations, real V10 history upgrade/replay, stale snapshots, races, failed-commit rollback, immutable retired artifacts, repeated replacement and delivery after retirement. Fresh V1–V11 initialization and the legacy upgrade regression passed.
- **249 Docker-dependent checks skipped.** Actual services: PostgreSQL 18.6 and Kafka 3.9.1. PostgreSQL 17.6 containers, multi-broker failover, mixed-version deployment and production-volume/load behavior remain unverified; Flyway warns that PostgreSQL 18 exceeds its tested support.
- An initial full round failed Kafka checks after Windows denied writes in the reused broker log directory. A fresh disposable broker resolved that environment issue; the final full run above passed. Logs remain in ignored workspace tooling: `.tools/milestone11-final-verify.log` and `.tools/milestone11-qa.json`.
- Documentation QA passed 187 local links/anchors across eight documents with balanced code fences. The workspace-owned broker and disposable PostgreSQL cluster were stopped after verification; ports 19092/19093/55432 have no listeners. Unrelated PostgreSQL on port 5432 was not changed.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ Milestones 1–11 functionality.

**CURRENTLY POSSIBLE:** ✓ replace maintained targets repeatedly, retire maintenance, inspect lifecycle and replay accepted decisions safely.

**NOT IMPLEMENTED YET:** ✗ physical purge/automatic retention, damaged-view repair, isolated-build cancellation, full merchant security or real provider/settlement integration.

**NEXT LOGICAL MILESTONE:** evidence-retention planning and observable operating limits before a separately reviewed bounded purge workflow.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Read-model lifecycle and maintenance ownership
- Atomic handover and bounded fanout
- Permanent identity and evidence retention
- Revision/target compare-and-set
- Idempotent operator commands
- Transaction isolation, propagation and advisory locks
- Deferred constraints and immutable audit
- Flyway upgrade, concurrency and Kafka integration testing
