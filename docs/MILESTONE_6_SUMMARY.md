# Milestone 6 — Event operations and audited outbox recovery

Completed 2026-10-22. Project: **fincore-payments — Distributed Payment Processing Platform**.

## 1. WHAT WAS IMPLEMENTED

- Local operator CLI: `overview`, `events`, `lag`, `failures`, `requeues`, and `requeue`.
- Outbox counts and metadata distinguish ready, blocked, backed-off, DEAD, and delivered work, including oldest blockers and consumer projection versions.
- Bounded Kafka lag diagnostics with explicit disabled/known/uninitialized/inconsistent/unavailable states; unknown totals remain null.
- Audited DEAD requeue with expected generation and idempotent request ID, preserving original event identity/payload.
- Fresh bounded retry cycles/backoff without resetting lifetime attempts or weakening stale-failure protection.
- Durable consumer failure evidence and resolution before offset acknowledgement; operator output excludes original key/value.
- Durable source-position markers prevent late old-owner failures reopening already successful processing.

## 2. CONCEPTS / TECHNOLOGIES USED

- Row locks, READ COMMITTED, and compare-and-set — concurrent recovery and stale intent.
- Request identity and immutable audit — safe replay of operator commands.
- Deferred PostgreSQL constraint triggers — recovery state and matching audit must commit together.
- Kafka Admin API and shared deadline — bounded progress observation without false zero lag.
- REQUIRES_NEW and source-position fingerprints — failure evidence survives rollback and success wins diagnostic races.
- Spring Boot PropertiesLauncher — packaged local CLI with HTTP, dispatcher, listeners, and topic creation disabled.

## 3. IMPORTANT FILES

- `EventOperationsCli.java` — validates operator commands and launches the headless application.
- `EventOperationsService.java` / `EventOperationsRepository.java` — metadata reads, locked requeue, exact command replay, and audit.
- `OutboxRepository.java` / `OutboxFailureRecorder.java` — current-cycle retry budget/backoff with monotonic lifetime CAS.
- `KafkaLagService.java` — bounded partition/group offset reports.
- `ConsumerFailureService.java` / `ConsumerFailureRepository.java` / `KafkaPaymentEventListener.java` — independent diagnostics and resolution sequencing.
- `V6__add_outbox_operations.sql` — retry metadata, enforced recovery transitions, immutable audit.
- `V7__add_consumer_failure_tracking.sql` — immutable original evidence and irreversible position-success markers.
- `EVENT_OPERATIONS_RUNBOOK.md` / `MILESTONE_6_LEARNING_GUIDE.md` — exact commands, concepts, impacts, alternatives, and costs.

## 4. REQUEST FLOW

```text
EventOperationsCli requeue
→ EventOperationsService.requeue
→ EventOperationsRepository.lockEvent / findRequest
→ DEAD + expected-generation check
→ metadata reset + immutable audit + deferred checks COMMIT
→ normal OutboxDispatcher.dispatchBatch later publishes the original event
```

## 5. IMPORTANT DESIGN DECISIONS

- Local execution and database access gate recovery; operator names are audit declarations, not authenticated identities.
- Lifetime attempts never reset; cycle attempts control exhaustion/backoff and generations identify recovery cycles.
- Recovery request replay returns its original result even after delivery; new intent needs a new request ID and current generation.
- Consumer source positions serialize diagnostic success/failure; healthy records also retain compact success markers.
- Observation does not skip poison records, repair their payloads, or certify projection completeness.

## 6. TEST STATUS

- **225** unit/service/configuration/MVC/CLI checks passed.
- **94** real PostgreSQL checks passed: 72 earlier regressions plus 22 operations/failure cases.
- **5** real Kafka/PostgreSQL checks passed, including durable failure resolution and known zero lag after all partitions drain.
- **2** packaged-CLI tests passed: headless inspection with an unreachable broker and idempotent recovery replay after delivery.
- A separate packaged `lag` check against an unreachable broker returned UNAVAILABLE / null total with `KAFKA_LAG_TIMEOUT`; startup plus observation took 14.85 seconds.
- Application compiled and packaged; V1–V7 migration/validation and legacy upgrade regression succeeded.
- Initial compilation needed the Boot 3.5 Kafka Admin method's SSL-bundles argument. One test-only exception-type assertion was corrected for Spring repository translation; its 22-test suite passed on rerun. No failures remain.
- **101 Docker-dependent methods skipped**; Docker Compose/PostgreSQL 17.6 and multi-broker failover remain unverified. Local database was PostgreSQL 18.6 (newer than Flyway's tested support); native broker was Kafka 3.9.1.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ milestones 1–6: lifecycle, request idempotency, ledger, outbox, Kafka/projection, event visibility and audited source recovery.

**CURRENTLY POSSIBLE:** ✓ inspect delivery blockers/lag/failures and safely restart a DEAD source event's retry cycle without repeating financial commands.

**NOT IMPLEMENTED YET:** ✗ consumer quarantine/correction/dead-letter replay, projection rebuilds, retention, network operator authentication, real provider/funds integration, production broker operations.

**NEXT LOGICAL MILESTONE:** controlled consumer recovery with explicit quarantine/completeness policy, protected decisions, correction/replay identity, and fault tests.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Operational state versus business state
- Retry cycles, generations, and monotonic counters
- Compare-and-set and stale operator intent
- Idempotent recovery commands
- Atomic audits and deferred constraints
- Kafka offset snapshots and unknown lag
- Transaction propagation for diagnostics
- Durable source-position fingerprints
- Rebalance races and irreversible success markers
- Headless packaged Spring Boot entry points
