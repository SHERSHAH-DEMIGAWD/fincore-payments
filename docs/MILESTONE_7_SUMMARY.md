# Milestone 7 — Controlled consumer quarantine and original replay

Completed 2026-10-22 in **fincore-payments — Distributed Payment Processing Platform**.

## 1. WHAT WAS IMPLEMENTED

- Local CLI commands: `quarantine`, `quarantines`, `gaps`, and original-record `replay`.
- Explicit quarantine of one observed permanent failure with expected fingerprint, request identity, operator/reason and accepted projection gap.
- Immutable quarantine/replay audit and original evidence preservation.
- Gap reports distinguish accepted unresolved quarantines from other open failures in the configured source/consumer/topic.
- Original replay applies the shared decoder, receipt deduplication and version guard; success and audit commit together.
- Live processing serializes with recovery and commits receipt/projection plus position resolution together before acknowledgement.

## 2. CONCEPTS / TECHNOLOGIES USED

- PostgreSQL row locks / READ COMMITTED — coordinate processing, diagnostics and competing operators.
- Idempotent recovery commands / SHA-256 — exact request replay and reviewed record identity.
- Flyway V8 / immutable audit / deferred constraints — preserve decisions and require atomic replay resolution.
- REQUIRED and REQUIRES_NEW — shared processing/replay commits; independent failure evidence.
- Kafka record acknowledgements — progress after durable processing or explicit quarantine.
- Projection completeness / version guards — track gaps and preserve newer snapshots during replay.

## 3. IMPORTANT FILES

- `ConsumerRecoveryService.java` — validates and coordinates quarantine, replay and scope.
- `ConsumerRecoveryRepository.java` — audit, original evidence reads and gap/quarantine reports.
- `ConsumerRecordProcessor.java` — locks positions and commits live processing/resolution together.
- `PaymentEventRecordDecoder.java` — shared strict contract for live records and original replay.
- `KafkaPaymentEventListener.java` / `ConsumerFailureRepository.java` — processor delegation and shared position identity.
- `EventOperationsCli.java` — validated headless recovery commands and explicit gap acceptance.
- `V8__add_consumer_recovery.sql` — immutable decisions and enforced replay/success consistency.
- `ConsumerRecoveryDatabaseTests.java` / `KafkaBrokerTests.java` / `LocalEventOperationsCliIT.java` — real transaction, offset and packaged-command coverage.
- `CONSUMER_RECOVERY_RUNBOOK.md` / `MILESTONE_7_LEARNING_GUIDE.md` — commands, explanations, alternatives and impacts; complete guide also updated.

## 4. REQUEST FLOW

```text
EventOperationsCli replay
→ ConsumerRecoveryService.replay
→ ConsumerFailureRepository.lockPosition
→ PaymentEventRecordDecoder.decode → PaymentEventConsumer.consume
→ ConsumerFailureRepository.resolve + ConsumerRecoveryRepository.insert
→ receipt / projection / success / audit COMMIT
```

## 5. IMPORTANT DESIGN DECISIONS

- Failed records block by default; quarantine explicitly accepts a visible gap for this read consumer.
- Quarantine preserves null success markers; only successful audited original replay closes its gap.
- Replay accepts preserved original text only; no arbitrary replacement or logical event-ID rewrite.
- Shared position locks arbitrate processing/recovery; accepted request retries return their original result.
- NO_KNOWN_GAPS describes tracked scope, not certified historical completeness.
- Local execution/database credentials gate commands; operator labels remain self-declared.

## 6. TEST STATUS

- Full Maven `verify`: **BUILD SUCCESS**, **358 executed checks passed**, zero failures/errors.
- **236** unit/service/configuration/MVC/CLI checks; **112** real PostgreSQL checks, including **18** new recovery cases.
- **7** real Kafka/PostgreSQL checks, including poison partition blocking/quarantine progress and original replay after a simulated compatible decoder fix.
- **3** packaged-CLI checks, including quarantine/gap/replay through the shipped JAR with an unreachable broker.
- Fresh V1–V8 migration, Hibernate validation and earlier legacy upgrade regression passed. An earlier targeted run failed because its new test database had not yet been created; setup was corrected and targeted/full runs passed.
- **121 Docker-dependent methods skipped**; Compose/PostgreSQL 17.6 container execution and multi-broker failover unverified. Actual local versions: PostgreSQL 18.6, Kafka 3.9.1; Flyway warns PostgreSQL 18 is newer than its tested support.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ milestones 1–7: payment foundation through audited source and consumer recovery.

**CURRENTLY POSSIBLE:** ✓ quarantine one permanent failure, resume its partition with a tracked gap, and replay unchanged evidence after a compatible fix without repeating financial operations.

**NOT IMPLEMENTED YET:** ✗ arbitrary corrections, reconciliation/rebuild, retention, raw-byte/dead-letter export, network operator authentication or real provider/funds integration.

**NEXT LOGICAL MILESTONE:** read-only projection reconciliation and explicit source-coverage/rebuild planning before source-backed correction.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Poison records and partition progress
- Quarantine and projection completeness
- Physical record versus logical event identity
- Database row locks and READ COMMITTED
- REQUIRED versus REQUIRES_NEW
- Idempotent recovery commands
- Immutable audits and deferred constraints
- Database commit before Kafka acknowledgement
- Original replay and version-guarded projections
- Reconciliation and rebuild coverage
