# Milestone 6 — visibility and audited event recovery

The previous milestones made payment changes durable, journaled them, saved immutable events, and delivered them through Kafka with a duplicate-safe consumer. This block addresses the next practical question: **when a dependency fails, how do we identify the stuck boundary and recover without changing financial history?**

Use the [operations runbook](EVENT_OPERATIONS_RUNBOOK.md) for commands and the [complete learning guide](COMPLETE_LEARNING_GUIDE.md) for earlier concepts. This guide explains what the new mechanisms change and why these choices fit the current project.

## 1. How this fits the whole platform

```text
Payment transaction: payment + optional ledger journal + outbox event
    ↓
Outbox delivery: bounded retry cycle → transport acknowledgement
    ↓
Kafka processing: receipt + newest projection → group offset commit
```

Milestone 6 observes all three boundaries without pretending they are one transaction. It adds source-event state inspection, broker/group lag, durable consumer failure evidence, and one audited recovery action for DEAD source events.

A delayed projection does not automatically mean payment capture failed. Source delivery may be exhausted, an earlier payment event may block delivery, Kafka may be behind, or a consumer record may be invalid. Separate evidence makes those cases distinguishable.

## 2. Lifetime attempts, retry cycles, and generations

`attempts` remains the lifetime count of delivery successes and recorded failures. `cycleAttempts` counts the current automatic retry cycle. `retryGeneration` counts explicit operator requeues. With five configured attempts, a DEAD event can have:

```text
Before recovery: attempts=5, cycleAttempts=5, retryGeneration=0, DEAD
After recovery:  attempts=5, cycleAttempts=0, retryGeneration=1, PENDING
After success:  attempts=6, cycleAttempts=1, retryGeneration=1, DELIVERED
```

The distinction preserves diagnostic history while giving a repaired dependency another bounded retry budget. The generation also lets an operator state exactly which recovery state they inspected.

Resetting the original lifetime counter would erase history and weaken its existing compare-and-set protection against stale failure reports. Keeping a single monotonically increasing counter without a separate cycle would immediately exhaust the old budget after requeue. Unlimited retries would avoid a terminal state but could hide an ongoing incident indefinitely. The chosen design keeps bounded automation and explicit recovery ownership.

## 3. Compare-and-set, row locks, and request idempotency

`EventOperationsService.requeue` uses three safeguards with different jobs:

| Safeguard | Question it answers |
| --- | --- |
| Row lock | Can two operators change this event simultaneously? |
| Expected generation and DEAD check | Is the state still the one this operator intended to recover? |
| Request ID with exact command match | Was this recovery already accepted before its response was lost? |

PostgreSQL row locks serialize conflicting writes. At READ COMMITTED, a fresh statement sees newly committed data, allowing a waiting request to find the first request's audit after acquiring the event lock. These database semantics support the project's recovery algorithm. [PostgreSQL locking](https://www.postgresql.org/docs/18/explicit-locking.html), [transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html).

An exact retry returns the original generation/timestamp even if delivery has progressed since then. Reusing the same request ID with a changed event, reason, operator, or expected generation conflicts. Concurrent reuse across different event locks is resolved by the audit table's unique request ID; the rejected transaction rolls back its own reset.

A JVM `synchronized` block would coordinate only one process. A blind SQL reset would accept stale operator intent. Requiring a fresh request ID after every network timeout could accidentally create multiple recovery actions. Row locks plus generation and request identity cover all three cases without introducing Redis or a distributed lock service.

## 4. Atomic audit and database enforcement

The reset and immutable audit commit together in one PostgreSQL transaction. V6 adds nonnegative counters, `cycleAttempts <= attempts`, a retry metadata trigger, an immutable audit trigger, and a deferred audit check. The database rejects lower lifetime attempts, lower generations, unaudited cycle resets, or an invalid generation transition.

The deferred check runs at commit so the reset and audit insert can occur in either order inside the transaction. Its matching audit includes previous counters and the requeue timestamp. Event identity and payload remain protected by the earlier outbox-envelope constraint.

Application-only validation would be easier but would leave direct SQL mistakes able to bypass these invariants. Logging “requeued” after committing the state would create a crash window with no durable audit. A separate audit database would require another delivery protocol. Storing the audit beside the outbox makes this specific invariant enforceable in one transaction; the cost is PostgreSQL-specific migration code and integration testing.

## 5. Ordering blockers and one-snapshot inspection

The existing relay preserves increasing versions per payment. A DEAD version 0 blocks a pending version 1 even if version 1's retry time has arrived. The operations repository reports the oldest blocking event ID so recovery can target the cause instead of repeatedly resetting later events.

The overview counts ready, blocked, and backed-off PENDING events using one SQL statement. These three buckets partition the pending count. Original occurrence timestamps make old waiting work visible. A row can count as ready while a dispatcher holds its lock: the report describes eligibility in a snapshot, not current worker availability.

Independent count queries could observe different moments and disagree. Cached JVM counters would need careful multi-process/crash reconciliation. A PostgreSQL query uses authoritative state already present here. Its cost grows with retained history; indexes help the access patterns, but retention and metrics export remain future work.

## 6. Consumer lag measures another boundary

`KafkaLagService` reads topic end offsets and the configured group's committed offsets with Kafka's Admin API. A known partition's lag is the difference between these positions. It uses a shared bounded deadline, reports partition states, and returns a total only when all required offsets are known. [Kafka Admin API](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/admin/Admin.html).

DISABLED, UNINITIALIZED, INCONSISTENT, and UNAVAILABLE differ from a known zero. A new consumer group with no commits does not have a measured zero backlog. An unreachable broker does not prove a caught-up consumer. PostgreSQL outbox acknowledgement also does not prove group progress.

Inferring lag from source DELIVERED counts would confuse broker acceptance with consumer processing. Assuming missing commits mean zero would produce reassuring but unsupported data. A dedicated monitoring stack could add historical charts and alerts later; this local diagnostic introduces the underlying measurement first.

## 7. Failure evidence survives a rolled-back consumer transaction

`KafkaPaymentEventListener` calls the transactional consumer. On failure, the receipt/projection transaction has rolled back before `ConsumerFailureService.failed` records evidence in a separate transaction. If diagnostics also fail, the original processing exception still propagates. The source record is not acknowledged as successful.

Evidence is keyed by source namespace, logical consumer, topic, partition, and offset. It retains original record key/value for investigation and updates attempt/timestamps for retries. The CLI shows positions and stable error codes while omitting raw bytes and exception text. A successful retry resolves the diagnostic record before listener return.

A rebalance adds another race: an old owner can fail after a new owner successfully processes that position. Simply reopening a failure row would leave a false OPEN incident. Both paths now lock a durable `consumer_event_positions` marker whose SHA-256 fingerprint distinguishes nullable key/value boundaries. Its successful-processing timestamp cannot be cleared. A late failure is recorded as resolved when success is already known, including success before the first failure ever existed. This adds a compact marker per physical record, including healthy ones; retention and storage growth are explicit costs.

Using the failed consumer transaction for diagnostics would roll the evidence back with the business work. Logs alone would be harder to query across restarts and retries. Returning success after recording a failure would advance processing while leaving the projection incomplete. The chosen design preserves evidence and the existing retry behavior; it adds database writes during repeated failure and therefore needs a future retention policy.

## 8. Why a source namespace matters

Partition 0, offset 10 identifies a position only within a particular topic history. Recreating a topic or replacing a cluster can reuse that position for different bytes. `FINCORE_KAFKA_SOURCE_NAME` distinguishes those histories; default `primary` is suitable only while that source history remains the same.

The repository refuses to replace earlier evidence with different key/value content at the same source position. A deliberate new namespace after recreation makes the new history explicit. Topic name alone cannot establish that identity. Namespace rotation does not reset consumer event-ID deduplication or rebuild projections.

## 9. Why this milestone keeps poison records blocking

Failure persistence is diagnostic evidence, not quarantine. Spring Kafka supports error handlers and dead-letter recovery, but this application continues retrying failed records without silently acknowledging them. [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

Skipping after a fixed number of failures would keep a partition moving but could discard a payment version from the read model. A dead-letter topic would require policies for durable transfer, ordering, authorised repair, replay identity, and resumption. Those tradeoffs are real; they are not solved merely by naming a dead-letter destination. This milestone makes the blockage inspectable while keeping that next design explicit.

Requeuing a source event retains its original identity and may create another broker delivery. The existing consumer receipt makes that safe for identical envelopes. It cannot repair an invalid record already occupying an earlier consumer offset.

## 10. Why a local CLI before an HTTP recovery API

`EventOperationsCli` runs the established Spring services with HTTP, schedules, listeners, and automatic topic creation disabled. It validates a small command vocabulary, outputs JSON, and closes the application context. Using the same dependency injection and transaction proxies preserves the application's database semantics.

An HTTP operator endpoint would be convenient but this project does not yet implement operator authentication, authorisation, or merchant access boundaries. A new endpoint would require that work first. Ad hoc SQL would bypass request idempotency and be harder to review. The CLI gives locally authorised operators one constrained workflow using their configured database access. It is not a substitute for a future access-control design.

## 11. Failure walkthrough and next implementation

Suppose Kafka is unreachable. The relay exhausts a cycle and marks a source event DEAD; later versions wait. After connectivity is repaired, an operator inspects the event, requeues the observed generation with one request ID, and checks the audit. The normal dispatcher publishes the same event. The consumer either processes its first delivery or recognises a duplicate; the projection advances only to a newer version. Source state, projection progress, lag, and failures provide separate completion evidence. Financial journals are not replayed.

The next logical block is **controlled consumer recovery**: define quarantine semantics, authenticated operator decisions, immutable repair/replay history, and tests showing exactly when partition progress may resume. Projection rebuilds and retention also need explicit identities and completeness checks. This milestone does not yet provide offset skipping, dead-letter replay, historical reconstruction, alerting, or production Kafka availability/security.

For exact tests run and remaining environment limits, see [MILESTONE_6_SUMMARY.md](MILESTONE_6_SUMMARY.md).

## TOPICS TO LEARN FROM THIS SESSION

- Operational state versus business state
- Retry cycles, generations, and lifetime counters
- Compare-and-set updates and stale operator intent
- Row locks and READ COMMITTED snapshots
- Idempotent recovery commands and unique request identities
- Atomic audit records and deferred constraint triggers
- Kafka offsets, consumer groups, and lag uncertainty
- Transaction propagation for failure diagnostics
- Source namespaces and topic recreation
- Poison records, quarantine, dead-letter handling, and replay
- Local CLI entry points and Spring transaction proxies
