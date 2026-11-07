# Milestone 21 — Independent settlement reconciliation

Implemented 2026-10-27. [Learning guide](MILESTONE_21_LEARNING_GUIDE.md) · [Runbook](SETTLEMENT_RECONCILIATION_RUNBOOK.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Signed cumulative merchant/currency statements from the independent sandbox provider journal.
- Merchant-owned exact-byte imports, metadata pages and original-document downloads.
- Immutable operation-level reports comparing external lines, local commands and LIVE journals.
- Principal, fee, identity, outcome, missing-source and unconfirmed-operation discrepancy reporting.
- Controlled attributed closure after fresh matching evidence for the same statement.
- Exact historical report/resolution retry and changed-intent conflicts.
- Database evidence immutability, ownership references and consistency guards.

## 2. Concepts / technologies used

- HMAC-SHA256 / strict UTF-8 JSON — authenticate original statement bytes.
- BigDecimal / PostgreSQL NUMERIC — exact financial totals.
- UUID idempotency / SHA-256 fingerprints — historical retries and source observations.
- Spring transactions / Repeatable Read / advisory locks — coherent evidence decisions.
- JDBC / Flyway / PostgreSQL constraints and triggers — append-only evidence.
- Merchant Basic authentication / CSRF — owned evidence API.
- JUnit / Mockito / MockMvc / real PostgreSQL / packaged HTTP — failure and recovery checks.

## 3. Important files

- `SettlementController.java` / `Settlement.java` — statement/report/resolution HTTP contracts.
- `SettlementImportVerifier.java` / `SettlementWire.java` — bounded authenticated strict documents.
- `SettlementService.java` — comparison, retry and closure policy.
- `SettlementRepository.java` — owned SQL, snapshots and immutable persistence.
- `SettlementProperties.java` / `application.yml` — opt-in keys and pinned instance.
- `SandboxProviderStore.java` / `SandboxProviderServer.java` — independent signed export.
- `V21__add_sandbox_settlement_reconciliation.sql` — evidence tables and guards.
- Settlement test suites / guide / runbook — verification and learning handoff.

## 4. Request flow

```text
POST /api/v1/merchant/settlement-statements/{statementId}/reports
→ SettlementController.reconcile
→ SettlementService.reconcile
→ SettlementRepository.stored + local
→ SettlementService.comparison
→ SettlementRepository.insertReport
→ PostgreSQL → Settlement.ReportResult
```

## 5. Important design decisions

- Independent provider files supply external evidence; statements never apply money.
- Signed original bytes are retained without JSON reformatting or archive expiry.
- Detailed operation issues determine matching; equal net totals are insufficient.
- Reports/resolutions are historical observations, protected by snapshot transactions.
- Merchant evidence commands serialize cooperatively without freezing payment writers.
- Closure needs a fresh matching fingerprint for the same exact statement; no override.
- Provider full-amount/zero-fee scope remains separate from M20 local partial accounting.
- Explicit byte/row caps refuse excess instead of returning partial matching evidence.

## 6. Test status

Final focused Java 21 Maven `verify` finished **BUILD SUCCESS**, **46 checks passed**, zero failures/errors, at 2026-10-27T15:59:05+05:30 (1:24 min): 23 unit/protocol/comparison/HTTP, 19 real PostgreSQL settlement cases and 4 packaged sandbox checks. This covers the final canonical-UUID/decimal validation and corrected restart-fixture cleanup.

The broad run ended at 15:56:15+05:30 (14:26 min) with zero assertion failures and **one Windows temporary-log cleanup error** after a forced sandbox restart. Its 684 unit and 440 PostgreSQL cases passed. The fixture now requires process exit and bounded redirected-file release; its complete 4-case packaged suite passed in the final follow-up. Financial and restart assertions remain intact.

Latest results per suite across the broad run and follow-up are below. This is **combined coverage**, not one successful full broad run; repeated suites are counted once, and two final wire cases extend the unit total.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / service / configuration / CLI | 686 | 0 |
| Real PostgreSQL | 440 | 0 |
| Packaged CLI / sandbox | 12 | 0 |
| Docker-dependent | 0 | 425 |
| Total | **1,138** | **425** |

New checks cover signed independent exports/restart, ambiguity/tampering/exact large decimals/canonical UUIDs, exact-byte replay, Basic/CSRF/ownership, lost-reply recovery, stale closure, concurrent source recovery, merchant contention, report/resolution rollback, hidden-issue SQL refusal, comparison budgets and V20→V21 preservation. Fresh V1–21 migration passed; V1–20 checksums match the prior M20 database. Packaged export survives restart; headless event CLI passes with deliberately invalid inherited enabled settlement settings. Earlier interrupted/focused runs are not added to the counts.

Actual environment: Windows, Java 21, PostgreSQL 18.6. Flyway warns PostgreSQL 18 exceeds its tested support range. Docker was unavailable; native Kafka broker delivery, Linux, production load/ingress, live vendor/bank settlement, key rotation and independent restore remain unverified in this session. Skips are not passes. Only this milestone's named disposable database had automatic table maintenance disabled to avoid prior NOWAIT/auto-analyze fixture contention.

Documentation links/anchors/fences and eight PowerShell runbook blocks pass static checks; the full interactive PowerShell demo was not manually executed. Final JAR covers application source/resources. Ignored evidence: `.tools/milestone21-verify.log`, `milestone21-final-focused.log`, `milestone21-broad-attempt-qa.json`, `milestone21-qa.json` and cleanup/checksum files. Workspace PostgreSQL on 55432 stopped with a completed shutdown checkpoint; data/logs were retained. No broker was started, unrelated PostgreSQL/project changed, or commit/push made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–21 functionality and the recorded regression/follow-up verification.

**CURRENTLY POSSIBLE:** ✓ Import independent signed sandbox statements, inspect exact evidence/discrepancies, recover the original provider command and record proof-based closure without duplicate financial effects.

**NOT IMPLEMENTED YET:** ✗ Bank payout/settlement execution, provider partial/fee contracts, cross-horizon correction/closure, Redis/risk, full observability, backup/restore closure and final deployment validation.

**NEXT LOGICAL MILESTONE:** 22 — Redis-backed controls and risk processing. Four proposed blocks remain, 22–25.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Independent reconciliation and settlement evidence
- HMAC authentication and exact-byte JSON
- Immutable observations and UUID idempotency
- Repeatable Read and advisory locks
- Source fingerprints and controlled resolution
- Database constraints and migration preservation
- Failure injection and concurrency testing
