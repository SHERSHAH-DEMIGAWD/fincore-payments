# Milestone 8 — Read-only projection reconciliation and coverage assessment

Completed 2026-10-22 in **fincore-payments — Distributed Payment Processing Platform**.

## 1. WHAT WAS IMPLEMENTED

- Headless `reconcile` CLI with optional payment filter, bounded pages and UUID continuation.
- Source/outbox/receipt/projection comparisons exposing missing, conflicting, stale, ahead and divergent records.
- Consumer-only artifacts remain visible through a union of candidate payment IDs.
- Retained event-history coverage and explicit NOT_CERTIFIED historical-completeness output.
- Strictly validated current-source replay/rebuild input assessment, without repair execution.
- Configured and other-scope recovery gap counts for the logical consumer.
- One read-only repeatable snapshot for totals, comparisons and gap context; metadata-only output.

## 2. CONCEPTS / TECHNOLOGIES USED

- PostgreSQL MVCC / REPEATABLE READ — consistent observations during concurrent writes.
- Read-only REQUIRES_NEW / timeout — report owns its transaction boundary.
- UUID keyset pagination — explicit bounded output and continuation.
- SQL UNION / lateral aggregates / EXISTS — include and compare different artifact sets.
- JSONB logical equality — distinguish harmless formatting from identity/content conflicts.
- Source coverage and logical consumer identity — assess rebuilding without certifying missing history.

## 3. IMPORTANT FILES

- `ProjectionReconciliationService.java` — snapshot transaction, findings and candidate assessment.
- `ProjectionReconciliationRepository.java` — read-only source/consumer comparisons and gap queries.
- `ProjectionReconciliationReport.java` / `PaymentReconciliation.java` — scoped report and per-payment metadata.
- `ReconciliationFinding.java` / `ReconciliationGapContext.java` — explicit findings and cross-scope incidents.
- `EventOperationsCli.java` — validated reconciliation options and headless routing.
- `ProjectionReconciliationDatabaseTests.java` — coverage, drift, identity, pagination, read-only and concurrency checks.
- `LocalEventOperationsCliIT.java` / `KafkaBrokerTests.java` — packaged no-broker report and live pipeline agreement.
- `PROJECTION_RECONCILIATION_RUNBOOK.md` / `MILESTONE_8_LEARNING_GUIDE.md` — commands, explanations, alternatives and rebuild planning; master guide/README/handoff updated.

## 4. REQUEST FLOW

```text
EventOperationsCli reconcile
→ ProjectionReconciliationService.reconcile
→ ProjectionReconciliationRepository.snapshot / page / gaps
→ PostgreSQL read-only REPEATABLE READ snapshot
→ findings + coverage + candidate metadata + pagination response
```

## 5. IMPORTANT DESIGN DECISIONS

- Reconciliation observes discrepancies; it does not mutate disputed facts or execute rebuilding.
- Latest-state agreement, receipt identity, retained history and quarantine gaps remain separate claims.
- Receipts/projections span source namespaces; other-scope incidents stay visible.
- JSONB compares logical envelopes; equal payment versions alone are insufficient.
- Pages have independent snapshots; no frozen multi-page export is claimed.
- Candidate inputs do not bypass existing receipts or certify historical completeness.

## 6. TEST STATUS

- Full Maven `verify`: **BUILD SUCCESS**, **392 executed checks passed**, zero failures/errors.
- **245** unit/service/configuration/MVC/CLI checks; **136** real PostgreSQL invocations, including **24** new reconciliation checks.
- **7** real Kafka/PostgreSQL checks, including reconciled lifecycle agreement; **4** packaged-CLI checks, including reconciliation with an unreachable broker.
- Concurrent-write test confirmed repeatable read/read-only status and stable source comparisons; all-table snapshots confirmed no application data changes.
- Fresh V1–V8 migration/validation and existing legacy upgrade regression passed; no new schema migration was needed.
- **145 Docker-dependent methods skipped**. Compose/PostgreSQL 17.6 containers, multi-broker failover and large-volume performance remain unverified. Actual local versions: PostgreSQL 18.6 and Kafka 3.9.1; Flyway warns PostgreSQL 18 is newer than its tested support.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ milestones 1–8: payment correctness through recovery and read-only projection reconciliation.

**CURRENTLY POSSIBLE:** ✓ inspect data agreement, retained history, global recovery context and candidate rebuild inputs without changing application data.

**NOT IMPLEMENTED YET:** ✗ executable rebuild/promotion, arbitrary corrections, frozen export jobs, retention, network operator authentication or real settlement reconciliation.

**NEXT LOGICAL MILESTONE:** isolated shadow-projection rebuild with explicit identity, validated coverage, catch-up checks and audited promotion/rollback planning.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Projection reconciliation
- MVCC and Repeatable Read
- Read-only transactions and REQUIRES_NEW
- Keyset pagination and snapshot lifetime
- SQL UNION, lateral aggregates and EXISTS
- JSONB equality and event identity
- History coverage versus latest-state agreement
- Logical consumer identity and source namespaces
- Deduplication versus rebuilding
- Shadow projections and promotion
