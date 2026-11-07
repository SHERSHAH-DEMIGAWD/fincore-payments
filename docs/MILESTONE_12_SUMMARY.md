# Milestone 12 — Retained evidence review and operating limits

Implemented and verified 2026-10-23. Documentation/handoff completed 2026-10-24 after the interrupted session resumed. Project: independent `fincore-payments`.

## 1. WHAT WAS IMPLEMENTED

- Authenticated, paginated evidence review scoped to the configured source/topic.
- Per-target lifecycle, departing decision/time, retained artifact counts and logical text-byte totals.
- Configurable retirement-age review states that always retain deletion prohibition.
- Configurable per-dataset comparison guard for new promote/rollback/replace/retire commands.
- Specific budget-refusal HTTP 409, with no enrollment/route/audit changes.
- Process-local routing-command counts and timings measured through transaction completion.
- Authenticated operating-limits report and V12 lookup indexes.

## 2. CONCEPTS / TECHNOLOGIES USED

- Read-only Repeatable Read / REQUIRES_NEW — internally consistent evidence reports.
- UUID keyset pagination — bounded target pages.
- PostgreSQL text-byte aggregation / BigInteger — logical retained-copy totals.
- Partial/composite indexes — departing-target lookup and scoped pages.
- Row budgets / existing transaction fence — early refusal of oversized new comparisons.
- Monotonic time / synchronized counters / fixed labels — local operation measurements.
- Validated configuration properties — review-window and comparison-limit startup checks.

## 3. IMPORTANT FILES

- `ProjectionEvidenceController.java` — evidence and operating-limits GET endpoints.
- `ProjectionEvidenceService.java` / `ProjectionEvidenceRepository.java` — snapshot, policy, paging and inventory.
- `ProjectionRoutingMetrics.java` / `ProjectionRoutingController.java` — HTTP operation outcomes through commit/rollback.
- `ProjectionRoutingService.java` / `ProjectionRoutingRepository.java` — comparison guard before new decision work.
- `ProjectionOperationsProperties.java` / configuration / `application.yml` — validated application settings.
- `V12__index_projection_retirement_evidence.sql` — lookup indexes; existing migrations preserved.
- Evidence, metrics, configuration, MVC/security and database tests — policy, isolation, concurrency and failure coverage.
- [Learning guide](MILESTONE_12_LEARNING_GUIDE.md) / [runbook](PROJECTION_EVIDENCE_RUNBOOK.md) — concepts, alternatives, impact and commands.

## 4. REQUEST FLOW

```text
GET /api/v1/operations/projection-routing/evidence
→ ProjectionEvidenceController.review
→ ProjectionEvidenceService.review
→ ProjectionEvidenceRepository.observationTime / page / inventory
→ read-only PostgreSQL snapshot
→ ProjectionEvidenceReport
```

## 5. IMPORTANT DESIGN DECISIONS

- Review age begins at the target's own retirement; uncertain timestamps retain evidence.
- Every review result keeps `deletionPermitted=false`; no deletion or immutable-history relaxation was added.
- Byte totals describe selected text copies, not physical or reclaimable disk space.
- New decisions check the row budget under the fence; exact accepted replays do not repeat comparisons.
- Measurements wrap the service proxy so deferred commit failures cannot count as success.
- Four fixed metric labels avoid per-request memory growth; counters reset on process restart.

## 6. TEST STATUS

- Full Java 21 Maven `verify` completed **BUILD SUCCESS** on 2026-10-23: **618 executed checks passed**, zero failures/errors — 352 unit/MVC/service/configuration/CLI, 254 real PostgreSQL (70 routing and eight evidence cases), seven real Kafka/PostgreSQL and five packaged CLI.
- Includes 31 added unit/MVC/configuration/metrics invocations and 14 added PostgreSQL cases. Evidence covers retirement correlation, all-generation/UTF-8 byte counts, scoped pagination, unchanged application tables, a concurrent retirement versus a read-only repeatable snapshot, exact budget boundaries, refused writes, replay after growth and metrics after a real deferred commit failure.
- Fresh V1–V12 startup, legacy upgrade and V10 routing-history upgrade/replay passed. V1–V11 files were preserved. The full run completed before the credit interruption; the resumed work changed documentation only. Source timestamps were checked against the packaged JAR.
- **263 Docker-dependent checks skipped.** Actual services were PostgreSQL 18.6 / Kafka 3.9.1. PostgreSQL 17.6 containers, production-volume/load, multi-broker availability and mixed-version deployments remain unverified; Flyway warns that PostgreSQL 18 exceeds its tested support.
- Evidence remains in ignored workspace `.tools/milestone12-verify.log`, targeted logs and `.tools/milestone12-qa.json`. Disposable services were confirmed stopped on resumption; the unrelated PostgreSQL service was not modified.
- Documentation QA on 2026-10-24 passed 207 local links/anchors across 11 documents, with balanced code fences and report counts independently aggregated from JUnit XML.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ Milestones 1–12.

**CURRENTLY POSSIBLE:** ✓ inspect retained target evidence, identify retirement-age review candidates, refuse oversized new routing work and inspect local command outcomes/timings.

**NOT IMPLEMENTED YET:** ✗ purge-plan approval/execution, physical deletion, automatic retention, persistent/cluster metrics, exporters/alerts, full merchant security or real provider/settlement integration.

**NEXT LOGICAL MILESTONE:** an audited, bounded purge-plan workflow with explicit preservation obligations and fresh preconditions, before implementing any physical deletion.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Evidence retention versus deletion authorization
- Snapshot isolation and read-only transactions
- Keyset pagination and observation scope
- Logical text bytes versus physical storage
- BigInteger and PostgreSQL numeric aggregation
- Partial/composite indexes
- Comparison budgets and transaction fences
- Transaction proxies and deferred commit checks
- Monotonic timing, synchronization and metric cardinality
- Configuration binding and startup validation
