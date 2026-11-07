# Milestone 13 — Audited bounded purge planning

Implemented 2026-10-24 in the independent `fincore-payments` project. [Learning guide](MILESTONE_13_LEARNING_GUIDE.md) · [Operator runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md).

## 1. What was implemented

- Authenticated creation, retrieval and fresh review of immutable retired-target plans.
- One explicit target per plan, capped at 1,000 total evidence records; overflow saves no partial plan.
- Saved record identities/SHA-256 fingerprints, retirement evidence, route revision and review policy.
- Principal-derived actor, bounded reason and exact accepted-request replay.
- Fresh route/policy/lifecycle/content drift reporting with preservation obligations.
- V13 storage constraints, retirement checks and update/delete guards; all responses retain `deletionPermitted=false`.

## 2. Concepts / technologies used

- Spring Security / CSRF — existing operator boundary reused for the plan API.
- Idempotent commands / revision checks — accepted retries and stale intent.
- PostgreSQL JSONB / SHA-256 — bounded artifact manifests and content comparisons.
- REQUIRES_NEW / Read Committed / fencing — fresh atomic plan creation.
- Read-only Repeatable Read — coherent nonblocking review observations.
- Flyway / foreign keys / triggers — durable immutable planning evidence.
- JUnit / Mockito / PostgreSQL integration tests — age, budgets, drift, concurrency and upgrades.

## 3. Important files

- `ProjectionPurgePlanController.java` — exposes create/get/review endpoints.
- `ProjectionPurgePlanService.java` — applies policy, attribution, retry and freshness checks.
- `ProjectionPurgePlanRepository.java` — hashes capped evidence and stores/reloads plans.
- `ProjectionPurgePlan.java` / `ProjectionPurgePlanCommand.java` — plan, review and command DTOs.
- `ProjectionEvidenceRepository.java` — adds shared exact source/topic/run lookup.
- `V13__add_projection_purge_plans.sql` — adds planning storage and integrity guards.
- `ProjectionPurgePlanDatabaseTests.java` / `ProjectionPurgePlanControllerTest.java` — database and HTTP verification.
- Learning guide/runbook — explain choices, alternatives, impact and operating steps.

## 4. Request flow

```text
POST /api/v1/operations/projection-routing/purge-plans
→ ProjectionPurgePlanController.create
→ ProjectionPurgePlanService.create
→ ProjectionRoutingRepository.fence
→ ProjectionPurgePlanRepository.artifacts / insert / find
→ PostgreSQL
→ ProjectionPurgePlan.Result
```

## 5. Important design decisions

- Plans require an aged retired target; warm shadows and isolated runs remain ineligible.
- The cap counts combined artifacts, not only target IDs; oversized targets fail atomically.
- Full-row fingerprints detect content changes that counts would miss; no artifact bodies are exported.
- Exact accepted retries precede fresh-work checks and return the saved historical plan.
- Reviews describe a snapshot and grant no approval/deletion capability.
- Existing evidence protections remain; financial/baseline/source/recovery records stay outside scope.

## 6. Test status

Final Java 21 full Maven `verify`: **BUILD SUCCESS — 650 executed checks passed, zero failures/errors**.

| Group | Passed | Skipped |
| --- | ---: | ---: |
| Unit / MVC / service / configuration / CLI | 369 | 0 |
| Real PostgreSQL | 269 | 0 |
| Real Kafka + PostgreSQL | 7 | 0 |
| Packaged CLI | 5 | 0 |
| Docker-dependent | 0 | 278 |

This adds 17 HTTP/security invocations and 15 real PostgreSQL cases over Milestone 12. Totals come from one final full run, not summed attempts. Fresh V1–V13 startup, legacy upgrade, V10 routing-history upgrade/replay and V12→V13 migration checks passed. Evidence: ignored `.tools/milestone13-verify.log` and independently aggregated `.tools/milestone13-qa.json`.

Actual local environment: PostgreSQL 18.6 and Kafka broker 3.9.1. Docker was unavailable, so PostgreSQL 17.6 containers remain unverified; Flyway warns that PostgreSQL 18 is above its tested version. Production load, multi-broker availability, mixed-version operation and physical purge were not tested. Initial disk-space/broker-startup attempts were interrupted; verified archives of stopped disposable Milestone 11/12 broker data restored headroom, and the final run used a healthy small test broker without application/assertion changes.

Coverage includes exact-budget/overflow behavior, unchanged other application tables, same-count content drift, historical replay after route changes, scope/policy mismatch, young/isolated/maintained refusals, attribution, immutable audit, concurrent fencing/snapshots and V12→V13 upgrade. Elapsed-age tests simulate observation time without rewriting immutable retirement history.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–13, including audited bounded retention planning.

**CURRENTLY POSSIBLE:** ✓ Record one aged retired target's explicit evidence inventory, retrieve original intent and recheck freshness without changing retained data.

**NOT IMPLEMENTED YET:** ✗ Verified archive/export, preservation acknowledgements, plan approval/execution, physical purge/automatic retention, real provider/settlement integration or production readiness.

**NEXT LOGICAL MILESTONE:** verified preservation/export manifests and authenticated review/approval tied to a fresh plan; bounded deletion requires a later separately designed executor.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Retention review and deletion authority
- Immutable audit and preservation dependencies
- Idempotency and revision checks
- JSONB and SHA-256 fingerprints
- Bounded manifests and sentinel rows
- Transaction isolation and REQUIRES_NEW
- Advisory locks and NOWAIT
- Security principals, roles and CSRF
- Flyway and integration testing
