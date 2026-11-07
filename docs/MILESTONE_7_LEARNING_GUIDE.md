# Milestone 7 — Controlled consumer quarantine and original replay

Implemented 2026-10-22 in independent `fincore-payments`. Start with the [complete learning guide](COMPLETE_LEARNING_GUIDE.md) for the earlier payment, idempotency, ledger, outbox and Kafka foundations. Use the [consumer recovery runbook](CONSUMER_RECOVERY_RUNBOOK.md) for commands and the [session summary](MILESTONE_7_SUMMARY.md) for verification results.

## Why this block matters

Milestone 6 made failed consumer records durable and inspectable. A malformed record still blocked later records on its Kafka partition forever. That preserved a strict processing boundary but left no controlled way to restore progress when the original record could not immediately be fixed.

Milestone 7 adds an explicit choice for this particular **latest-payment projection consumer**: keep blocking, or accept one documented gap and let the partition progress. The decision is scoped to an existing failure, immutable fingerprint, source namespace, logical consumer and topic. A separate replay can close the gap only when the preserved original passes the same current contract as live records and its database work commits.

This is useful operational progress, with a cost: the projection can be incomplete while quarantined records remain. Payments and the accounting journal retain their own source transactions. Quarantine never calls capture/refund or posts accounting entries. The policy must not be copied automatically into a future settlement or notification consumer, where skipping one input may have a different consequence.

## The state model

```text
record fails → OPEN durable failure → default: retry and block
                                  │ explicit quarantine command
                                  ▼
                      immutable QUARANTINE audit
                      processed_at remains null
                      visible unresolved gap
                                  │ original record replay after compatible fix
                                  ▼
                      strict decode + receipt + projection
                      processed_at + resolved_at + REPLAY audit
                      one PostgreSQL COMMIT
```

Quarantine accepts a disposition for acknowledgement. It does not mean the event was applied. `ConsumerRecoveryRepository.isQuarantined` remains true even after successful replay, so redelivery of the original physical record can safely return without trying its old wire representation again.

`gaps` returns `DEGRADED` when the configured source/consumer/topic has unresolved quarantines or other open failures. It returns `NO_KNOWN_GAPS` when neither is observed. That wording is deliberate: no known failures cannot prove that old events existed, were retained, or were consumed before this system began tracking them. Rotating the source namespace changes the report's scope; investigate older namespaces before treating an incident as closed.

## Concepts, practical effects and alternatives

| Concept / choice | Where it is used and why | Alternative and tradeoff |
| --- | --- | --- |
| Explicit quarantine | CLI requires `--accept-projection-gap=true`; only an observed open INVALID_EVENT or EVENT_CONFLICT is eligible. | Automatic dead-letter recovery keeps progress without intervention, but would accept missing projection inputs without an operator decision. It needs a different completeness and alerting policy. |
| Database quarantine | Immutable audit references existing original evidence; no second broker send is needed. | A Kafka dead-letter topic is useful for transport decoupling, but introduces another acknowledgement/commit boundary and its own retention/access/replay lifecycle. |
| Expected fingerprint | A command includes the exact lowercase SHA-256 from `failures`; position identity cannot silently change underneath recovery. | Failure ID alone identifies the row, but omits an explicit check that the operator reviewed this preserved record identity. |
| Shared row lock | Processing, diagnostics, quarantine and replay lock the same source-position row before making a decision. | A Java lock only coordinates one JVM. Separate state checks without the common database lock allow a processing/quarantine race. |
| READ COMMITTED | After waiting for a position lock, commands re-read state and request identity in the current committed database state. | An earlier unlocked lookup is useful for locating immutable evidence, but cannot arbitrate a later recovery decision. |
| Idempotent operator command | Request UUID plus exact action/failure/fingerprint/operator/reason replays the original accepted result. | Retrying with a fresh request every time can produce ambiguous duplicate intent. Retrying a changed command under the same ID is rejected. |
| Immutable audit | V8 rejects audit UPDATE/DELETE and permits only one QUARANTINE and one successful REPLAY per failure. | Mutable status alone loses the original decision, identity and reason. A database administrator can still bypass application protections; this is not a tamper-proof external audit service. |
| Deferred constraints | Commit requires both replay audit and successful position/failure resolution; quarantine alone cannot manufacture success. | Only service validation is simpler but leaves persisted relationships easier to violate through another writer. Triggers add SQL maintenance/testing cost. |
| Original-only replay | `PaymentEventRecordDecoder` validates preserved decoded key/value text and `PaymentEventConsumer.consume` applies it. | Accepting arbitrary replacement JSON makes event identity and coverage ambiguous, especially when the original JSON cannot identify an event. A correction protocol needs a separate design. |
| Shared processing transaction | `ConsumerRecordProcessor.process` combines receipt, version-guarded projection and success marker under one transaction. | M6 committed projection before separate diagnostic success. That was retry-safe, but recovery now needs one lock/transaction to arbitrate disposition and application together. |
| Local operations boundary | Packaged CLI starts with no HTTP, listeners, dispatcher or topic creation. | A network recovery API would require real authentication, authorization and ownership controls. The local operator string remains a self-declared audit value. |

These alternatives are not universally worse. They are less suitable for this block's existing database evidence, local operator interface and narrow projection scope. No throughput comparison was performed.

## Why live processing changed

Previously the listener called the consumer transaction, then recorded diagnostic success in a separate transaction. Quarantine adds a competing decision that must coordinate with successful processing. The new listener delegates to `ConsumerRecordProcessor.process`, which performs:

1. Insert/find and lock the source-position marker, rejecting a changed key/value fingerprint.
2. If a durable quarantine exists, return without setting a successful-processing marker.
3. Otherwise decode the original record, validate the envelope/key and consume it.
4. Resolve the source position and failure in that same transaction.
5. Return through the transaction proxy only after commit; then the Kafka container can acknowledge.

`PaymentEventConsumer.consume` uses the default REQUIRED propagation, so its receipt/projection joins this outer transaction. If resolution fails after projection SQL executes, the whole transaction rolls back. On failure, the listener records diagnostic evidence through `ConsumerFailureService.failed` in an independent REQUIRES_NEW transaction and rethrows the original failure.

Do not call `ConsumerFailureService.resolved` from inside the new position-locked processing transaction: its REQUIRES_NEW transaction would wait for a lock held by the transaction it suspended. The processor calls `ConsumerFailureRepository.resolve` directly to join its current transaction. The existing independent service method remains available to the earlier diagnostic checks; V8 prevents it resolving a quarantined record without replay audit.

The Kafka consumer keeps auto-commit disabled, `AckMode.RECORD`, and unlimited retry on failure. No Admin API offset mutation or error-handler skip was added. These acknowledgement semantics are described in the [Spring Kafka listener container reference](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html) and [error-handling reference](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

## Replay identity and crash cases

The **recovery request ID** identifies an accepted operator command. The **event ID** still identifies the original logical event. The **source position/fingerprint** identifies the physical decoded record. A replay command creates no new event ID and publishes no replacement record.

| Failure or race | Persisted result |
| --- | --- |
| Two identical quarantine requests race | One audit; the other returns the original result with `replayed=true`. |
| Different quarantine requests race for one failure | One decision wins; the other conflicts. |
| Live processing wins before quarantine | Successful processing remains; stale quarantine is rejected. |
| Quarantine wins before live processing | Listener observes the disposition; no projection/success marker is manufactured. |
| Database commit succeeds but Kafka offset commit is lost | Redelivery sees durable quarantine or idempotent processing receipts. |
| Original is still malformed | Replay throws, no REPLAY audit commits, and the gap remains. |
| Replay audit or success-marker write fails | Receipt, projection and resolution roll back with the audit. |
| CLI output is lost after successful replay | Identical request returns the original accepted result. |
| A newer payment projection arrived before replay | Older event receipt is recorded; version-guarded upsert keeps the newer snapshot. |

A late failure from a previous partition owner cannot reverse an existing successful position marker. Existing M6 bookkeeping still retains that evidence as resolved. A late failure after quarantine may update diagnostic counts, while the quarantine audit and unresolved gap remain.

## Boundaries to remember

- Quarantine alone never closes a gap, even if a newer full snapshot makes the latest visible payment state look correct.
- Original replay can recover a record rejected by earlier code after a compatible decoder/consumer fix. It cannot make genuinely malformed JSON valid or repair an identity conflict without a separate correction design.
- Evidence is StringDeserializer-decoded text, not a raw Kafka byte/header archive.
- `failures --state=OPEN` includes unresolved quarantined failures. Use `quarantines` and `gaps` to distinguish accepted gaps from other blocking failures.
- Only successful recovery decisions have accepted request/audit rows. Failed replay leaves its original gap and returns an error; rejected attempts do not create a second accepted decision.
- Quarantine applies to one physical source position. A duplicate at another offset needs its own observed failure and decision.
- Database/broker commits remain separate; the guarantee remains at-least-once transport with idempotent PostgreSQL effects.
- Keep CLI and listener on the updated build. An older listener does not consult quarantine; it can keep blocking or attempt resolution which V8 rejects.
- No retention, alerts, production authorization, projection rebuild, arbitrary correction or funds movement was introduced.

## Holistic project direction and next implementation

The project has progressed from payment state correctness, to retry safety, accounting history, durable publication, idempotent consumer effects, incident visibility, and now explicit recovery choices. This block matters because recovery itself changes what the system promises: partition progress can resume while the missing-input obligation remains visible and auditable.

The next logical milestone is **projection reconciliation and rebuild planning**: compare authoritative source/outbox coverage with projection versions and known gaps, produce a read-only discrepancy report, and define a source-backed correction identity before any automatic repair. Historical gaps before V4, topic recreation, namespaces and logical consumer identities must be explicit. Retention/archive planning and network operator authentication remain separate follow-up work.

## TOPICS TO LEARN FROM THIS SESSION

- Kafka partition blocking and poison records
- Quarantine and projection completeness
- Physical record identity versus logical event identity
- Database row locks and READ COMMITTED
- REQUIRED versus REQUIRES_NEW
- Idempotent operator requests
- Immutable audits and deferred constraints
- Database commit before Kafka acknowledgement
- Original-record replay and version-guarded projections
- Reconciliation and rebuild coverage
