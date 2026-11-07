# Milestone 20 — Partial payment operations and explicit fees

Implemented 2026-10-26. [Learning guide](MILESTONE_20_LEARNING_GUIDE.md) · [Runbook](PAYMENT_ACCOUNTING_RUNBOOK.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Merchant-owned partial capture and capture-specific refund commands on manually authorized payments.
- Immutable amount-mode enrollment, request UUID replay and explicit expected-version checks.
- Exact capture fee intent, separate fee journals and retained-fee refund policy.
- Cumulative capacity limits, partial lifecycle statuses and current/historical accounting snapshots.
- Shared ledger/balance reads with command identity and platform fee revenue.
- Atomic command/payment/journal/outbox/merchant-notification enrollment with PostgreSQL guards.
- Compatible lifecycle-event consumers/projections and protection against mixing provider/full/amount workflows.
- Milestone guide, runnable accounting demo, regression tests and documentation continuation.

## 2. Concepts / technologies used

- BigDecimal / NUMERIC(19,2) — exact principal and fees.
- Immutable command records / UUID idempotency / source versions — accepted intent and historical replay.
- Double-entry journals — gross principal, capture refunds and fee revenue.
- JPA + JDBC / REQUIRES_NEW / row locks — atomic aggregate decisions.
- Flyway / partial unique indexes / deferred foreign keys and triggers — journal and lifecycle consistency.
- Transactional outbox / lifecycle enums — ordered events and reliable merchant enrollment.
- JUnit / Mockito / MockMvc / real PostgreSQL — rollback, concurrency and migration evidence.

## 3. Important files

- `PaymentAccountingController.java` / `PaymentAccounting.java` — owned amount-command and accounting API.
- `PaymentAccountingService.java` — intent validation, amount limits, transaction and historical replay.
- `PaymentAccountingRepository.java` — locked source, immutable commands and exact totals.
- `Payment.java` / `PaymentStatus.java` / `PaymentEventType.java` — partial lifecycle state and event types.
- `LedgerTransaction.java` / `LedgerOperation.java` / `LedgerAccount.java` / `LedgerRepository.java` — operation-linked principal and fee journals.
- `V20__add_partial_payment_accounting.sql` — amount enrollment, commands and independent database guards.
- `PaymentService.java` — refuses full/manual mutations after amount enrollment.
- `EventOperationsCli.java` — disables API merchant webhook scheduling/credential requirements in headless commands.
- New accounting tests / guide / runbook — verification and learning handoff.

## 4. Request flow

```text
POST /api/v1/payments/{paymentId}/amount-operations
→ PaymentAccountingController.submit
→ PaymentAccountingService.submit
→ PaymentAccountingRepository.lock + accepted-request lookup/insert
→ Payment.applyAmounts + PaymentRepository.flush
→ LedgerTransaction.amountPosting + LedgerRepository.save
→ OutboxService.record → merchant delivery enrollment
→ PostgreSQL deferred commit checks
→ PaymentAccounting.Result
```

## 5. Important design decisions

- Gross principal and fees use separate immutable two-leg journals.
- Refunds identify one capture and cannot consume another capture's principal.
- The first refund closes unused capture capacity; capture fees remain retained.
- Ledger-derived totals avoid mutable financial counters; historical replay uses the immutable command prefix.
- Source row locks serialize aggregate capacity; expected version rejects stale new intent.
- Amount mode begins before the first local capture and never mixes with full provider commands.
- Existing event shape retains authorized amount; the accounting API exposes all partial/fee totals.
- The lifetime 100-command cap bounds this learning workflow and is an explicit production limitation.

## 6. Test status

Broad Java 21 Maven `verify` finished **BUILD SUCCESS** at 2026-10-26T19:20:50+05:30 in 13:11 min: **1094 executed checks passed**, zero failures/errors. The table reports that one broad run; earlier focused/failed runs are not added.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / service / configuration / CLI | 662 | 0 |
| Real PostgreSQL | 421 | 0 |
| Packaged CLI | 11 | 0 |
| Docker-dependent | 0 | 406 |

After the final monotonic-timestamp hardening, a focused Java21 Maven `verify` finished **BUILD SUCCESS** at 2026-10-26T19:24:18+05:30 in 02:06 min: **102 executed checks passed**, zero failures/errors. It reruns the complete Payment domain matrix/exact-money suite plus native accounting and legacy ledger suites against the final packaged source. This overlapping follow-up is reported separately, not added to the broad total.

Selection: `-Dit.test=LocalPostgres*IT,LocalEventOperationsCliIT,Packaged*IT,*IntegrationIT`. The native Kafka broker suite was not selected/run. Docker was unavailable, so container skips are not passes. Actual Windows / PostgreSQL 18.6; PostgreSQL 17.6 containers, Linux, load, public ingress, multi-instance/mixed-version behavior, live funds/provider partial operations and independent backup/restore remain unverified. Flyway warns PostgreSQL 18 exceeds its tested support range.

New coverage includes eight money-boundary invocations, eight additional legacy state-matrix combinations, a monotonic-timestamp regression and 19 accounting PostgreSQL cases: split captures, capture-specific refund limits, retained fees and negative payable, exact historical replay, stale/changed intent, actual Basic/CSRF/ownership, concurrent capacity, late rollback, missing-fee commit refusal, immutable rows, legacy/full/provider isolation, lifecycle projection ordering, the exact 100-command cap and a V19→V20 payment before-image. The first broad run exposed eight old fixture cases that left new partial states in CREATED; the helper now constructs those states and all original transition assertions remain. The passing broad run preceded timestamp hardening; the final focused verification covers that change and the final JAR. Headless packaged CLI inherits deliberately invalid enabled merchant-webhook settings and passes with explicit CLI disable flags.

Only the named disposable `fincore_milestone20_check` database had automatic table maintenance disabled to avoid known NOWAIT/auto-analyze test contention. No production/other database settings or application locking were changed. The isolated workspace PostgreSQL cluster was stopped after verification; its shutdown checkpoint completed, and no listener remains on 55432. No Kafka broker was started. Existing test databases/logs remain preserved; PostgreSQL 5432 and the separate project were untouched.

Ignored workspace evidence: `.tools/milestone20-verify.log`, `.tools/milestone20-final-focused.log`, earlier focused/build/first-fixture-attempt logs, `.tools/milestone20-broad-qa.json`, `.tools/milestone20-qa.json` and `.tools/milestone20-cleanup.json`. Final application sources/resources predate the tested JAR. Markdown links/anchors/fences are checked. No commit or push was requested or made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–20 implementation and the recorded regression verification.

**CURRENTLY POSSIBLE:** ✓ Locally split captures, allocate principal refunds to captures, post explicit fees, inspect accounting/journals and recover accepted requests without duplicate effects.

**NOT IMPLEMENTED YET:** ✗ Provider partial HTTP/callback contracts, fee tariffs/refunds, remote void/release, settlement, Redis/risk, full observability, independent restore/retention closure and final deployment validation. No live vendor/funds.

**NEXT LOGICAL MILESTONE:** 21 — settlement and independent sandbox reconciliation. Five proposed blocks remain, 21–25.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Partial capture and capture-specific refunds
- Gross principal, fees, payable and retained-fee debt
- Immutable journals and reversal references
- Request UUID idempotency and expected versions
- Row locks and aggregate financial invariants
- Transaction ownership and rollback
- Deferred PostgreSQL constraints and foreign keys
- Exact decimal precision and scale
- Historical snapshots from immutable commands
- Lifecycle-event compatibility
- Settlement reconciliation
