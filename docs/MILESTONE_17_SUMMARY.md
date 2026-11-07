# Milestone 17 — Sandbox payment-provider integration

Implemented 2026-10-25. [Learning guide](MILESTONE_17_LEARNING_GUIDE.md) · [Provider runbook](PROVIDER_INTEGRATION_RUNBOOK.md) · [Remaining roadmap](ROADMAP.md).

## 1. What was implemented

- Merchant-owned provider AUTHORIZE, CAPTURE and full REFUND commands with progress/detail reads.
- Separate authenticated local HTTP sandbox with a restartable decision journal.
- Durable command identity, immutable intent and per-payment provider binding.
- Exact retries for lost replies, crashed attempts and rolled-back local completion.
- Leases, stale-worker fencing and one unfinished command per payment.
- Confirmed outcomes reuse atomic payment, balanced-ledger and outbox writes.
- Uncertain outcomes preserve payment state; managed payments reject manual transition bypass.

## 2. Concepts / technologies used

- Provider adapter / Java 21 HttpClient — separate bounded HTTP execution.
- Durable idempotency / uncertainty — stable remote keys and exact intent recovery.
- Spring transaction propagation — short independent database work, no transaction around HTTP.
- PostgreSQL locks / leases / fencing / deferred guards — coordinated attempts and financial application.
- Strict versioned JSON / atomic file publication — sandbox protocol and restartable decisions.
- JUnit / Mockito / real PostgreSQL / Kafka / executable processes — failure and regression checks.

## 3. Important files

- `ProviderOperationController.java` / `ProviderOperation.java` — owned command/progress API and wire records.
- `ProviderOperationService.java` / `ProviderOperationTransactions.java` — durable orchestration and confirmed completion.
- `ProviderOperationRepository.java` — bindings, attempts, leases, result persistence and paging.
- `PaymentProvider.java` / `HttpSandboxPaymentProvider.java` / `ProviderWire.java` — adapter, capped transport and strict matching.
- `SandboxProviderServer.java` / `SandboxProviderStore.java` — standalone sandbox and durable replay journal.
- `PaymentService.java` / `V17__add_durable_provider_operations.sql` — guarded financial application and database invariants.
- Provider test suites / learning guide / runbook — migration, failure, process-restart evidence and teaching handoff.

## 4. Request flow

```text
POST /api/v1/payments/{paymentId}/provider-operations + merchant Basic + CSRF
→ ProviderOperationController.submit → ProviderOperationService.submit
→ ProviderOperationTransactions.prepare/claim → commit
→ HttpSandboxPaymentProvider.execute → SandboxProviderStore.execute
→ ProviderOperationTransactions.finish → PaymentService.applyProviderResult
→ PostgreSQL payment + ledger + OutboxService.record → ProviderOperation.Result
```

## 5. Important design decisions

- Persist intent and claim before HTTP; provider I/O joins no active Spring transaction.
- Keep one provider operation UUID across attempts; lease tokens change only worker ownership.
- Preserve UNKNOWN separately from a confirmed decline; original-command retries recover uncertainty.
- Bind at first authorization and guard manual transitions at service and database layers.
- Commit confirmed local state, journal, event and command completion together.
- Use an opt-in local sandbox with retained instance/journal; no live funds or power-loss guarantee.

## 6. Test status

Full Java 21 Maven `verify` finished **BUILD SUCCESS** at 2026-10-25T13:55:09+05:30 in 09:37 min: **914 executed checks passed**, zero failures/errors. Counts come from one final full run; focused reruns are not added.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / service / configuration / CLI | 533 | 0 |
| Real PostgreSQL | 364 | 0 |
| Real Kafka + PostgreSQL | 7 | 0 |
| Packaged CLI | 10 | 0 |
| Docker-dependent | 0 | 363 |

Adds 51 unit/MVC/configuration/HTTP checks, 31 PostgreSQL invocations and two packaged sandbox cases over Milestone 16. New coverage includes actual merchant/CSRF ownership, approval/decline/uncertainty, exact replay, one active call, crash/expired lease, stale completion, remote acceptance followed by local rollback, manual/SQL guards, coherent progress snapshots, inherited transaction suspension and V16 before-image preservation. The packaged sandbox is forcibly killed and restarted to recover its original accepted decision. Existing operator CLI tests deliberately inherit invalid enabled-provider credentials and still pass through the CLI's explicit disable override.

Earlier focused testing found PostgreSQL lock SQLSTATE `55P03` wrapped as `UncategorizedSQLException`; the service now maps that specific contention to 409. A later test-only compile attempt needed a checked-exception declaration in the new transaction-suspension test. Earlier setup/import issues and corrected attempts remain in ignored logs. No financial or authorization assertion was relaxed. Final verification used a fresh disposable database. Evidence: `.tools/milestone17-verify.log`, focused logs and independent JUnit aggregation `.tools/milestone17-qa.json`.

Actual PostgreSQL 18.6 / Kafka broker 3.9.1 on Windows. Docker was unavailable, so PostgreSQL 17.6/container checks skipped; Flyway warns about PostgreSQL 18 tested support. Linux, production load, multi-broker/mixed-version deployment, live vendors, power-loss durability, external backup/restore and multi-instance CSRF remain unverified. Local sandbox process restart is tested; it does not certify those broader guarantees.

Stopped only the verified session Kafka process and workspace PostgreSQL cluster, which completed its shutdown checkpoint. No listeners remain on 55432/19092/19093; unrelated PostgreSQL 5432 and the separate project were untouched. Final application source/resources predate the tested JAR. No commit or push was requested or made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–17 implementation, including sandbox provider commands and recovery.

**CURRENTLY POSSIBLE:** ✓ Merchant-owned sandbox authorization, capture and full refund with exact retries and existing ledger/event delivery.

**NOT IMPLEMENTED YET:** ✗ Incoming/outgoing webhooks, live vendors, automatic provider recovery/review tools, void/partials/fees, settlement, Redis/risk, full observability, restore/retention closure and final deployment hardening.

**NEXT LOGICAL MILESTONE:** 18 — incoming provider webhooks. Approximately eight proposed blocks remain, 18–25; see the roadmap's finish criteria.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Provider adapters and distributed uncertainty
- Durable idempotency and provider namespaces
- Spring transaction propagation and proxy boundaries
- PostgreSQL row locks, leases and fencing
- Atomic financial application and transactional outbox
- Strict JSON and bounded HTTP deadlines
- Atomic file publication and process recovery
- Fault injection, concurrency and upgrade testing
