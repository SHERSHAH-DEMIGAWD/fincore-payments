# Milestone 5 — Kafka delivery and consumer projection

## Why this phase exists

Earlier milestones made payment changes, ledger postings, and source events atomic in PostgreSQL. This phase takes those committed events across a process boundary and builds an independent read model. It teaches what happens when the database and message broker succeed or fail separately. It does not add actual money movement or a second financial ledger.

The default transport is still `local`. Selecting `FINCORE_EVENT_TRANSPORT=kafka` enables Kafka components; `FINCORE_OUTBOX_ENABLED=true` also enables publication. The default broker address is `localhost:9092`, topic `payment-events.v1`, and group `fincore-payment-projection-v1`. Spring Boot manages the compatible Spring Kafka and Kafka client versions.

The comparisons below describe fit for this milestone. The alternatives are useful designs with different requirements, not universally worse technologies.

## 1. Transactional outbox: separate acceptance from delivery

`PaymentService` already saves each `PaymentEvent` in the transaction that changes the payment and, when applicable, its ledger. `OutboxDispatcher` later calls `PaymentEventPublisher`. `KafkaPaymentEventPublisher.publish` implements that boundary for Kafka; the HTTP request does not wait for Kafka.

A direct database-plus-broker write inside a controller leaves a gap: the database can commit while publication fails, or publication can succeed while the database rolls back. The outbox makes the intent to publish durable alongside the payment. It moves the remaining uncertainty to delivery, where retry is explicit.

An in-memory application event is simpler for work that may safely disappear on restart. It cannot replace a durable delivery record here. Direct HTTP delivery is legitimate for a synchronous dependency, but would require its own durable retry, timeout, and deduplication rules for this asynchronous workflow.

## 2. Kafka: an event log with independently progressing readers

The topic carries immutable payment snapshots. A consumer group tracks its position with offsets; a future distinct group could build another view without sharing this projection's progress. Retention can permit replay while records remain available. Retention is finite and needs an explicit operational policy.

Kafka fits the learning objective of a replayable event stream. RabbitMQ is also a valid choice, especially for routed work queues; comparing real deployments would require routing, retention, ordering, and operational requirements. This milestone does not benchmark either broker or establish a throughput advantage. See [Kafka's design](https://kafka.apache.org/39/design/design/).

The publisher uses `paymentId` as the key. Related records map to the same partition under a stable partitioning setup, so payment ordering does not depend on random placement. There is no global order across payments. Changing partition counts also deserves planning rather than assuming an eternal per-key partition mapping.

## 3. Broker acknowledgement is a boundary, not end-to-end completion

The producer enables idempotence, requests `acks=all`, and bounds blocking/delivery time. The dispatcher marks DELIVERED only after the send is acknowledged. Producer idempotence protects Kafka's producer retry protocol; it does not deduplicate a fresh application send after a database rollback. `acks=all` waits for the current in-sync replicas, subject to broker/topic configuration. A one-broker Compose setup still has only one copy and no broker-failure redundancy. See [Kafka producer configuration](https://kafka.apache.org/39/configuration/producer-configs/).

The outbox row lock remains held during the bounded network send. That makes ownership and crash rollback easy to reason about, but a slow broker occupies a database transaction longer. A claim/lease design could release the lock sooner, at the cost of lease expiry, worker fencing, and recovery logic. This smaller phase accepts that tradeoff without claiming production capacity.

## 4. At-least-once delivery needs durable consumer idempotency

`PaymentEventConsumer.consume` writes an immutable receipt to `consumed_payment_events` and updates the projection in one `@Transactional` service call. Database uniqueness arbitrates concurrent consumers and survives restarts. A JVM `Set` would disappear on restart and would not coordinate multiple instances.

The receipt identifies the logical consumer and event ID. An exact logical replay is harmless. The same event ID with different content is a conflict, rather than a duplicate to discard. Another uniqueness rule rejects different event IDs claiming the same payment version. These checks distinguish a retry from inconsistent event history.

The durable identity is `payment-projection-v1`, separate from the configurable Kafka group. Changing the group can change offset progress, but does not erase receipts or rebuild the projection. A deliberate rebuild needs a separate identity/data-management plan.

Producer idempotence and consumer idempotency solve different gaps. Neither makes PostgreSQL and Kafka one atomic resource. Kafka transactions alone can coordinate Kafka records and offsets; they do not automatically include these PostgreSQL writes. This project deliberately describes its guarantee as at-least-once transport with idempotent database effects.

## 5. Commit the database before advancing the offset

`KafkaPaymentEventListener.onMessage` calls the separate transactional service. When that proxied service returns successfully, its database transaction has committed. `KafkaEventConfiguration` disables auto-commit and selects `AckMode.RECORD`, so the container advances the offset after successful listener processing. Keeping the transaction in a separate bean matters because Spring's transaction interceptor must actually run. See [Spring Kafka listener commit modes](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html).

Committing an offset first would allow a crash to permanently skip an unapplied database change. Committing the database first instead creates a repeatable-delivery window, handled by the durable receipt.

### A concrete failure walkthrough

1. A capture commits its payment, balanced ledger entries, and outbox event `E`.
2. Kafka acknowledges `E`, but the publisher process stops before the outbox DELIVERED transaction commits.
3. The row lock is released by rollback. A later dispatcher publishes `E` again with its original event ID.
4. The consumer commits the first receipt and projection. If it stops before its offset commit, Kafka can deliver `E` yet again.
5. The stored receipt recognizes the unchanged envelope. No additional projection effect occurs, and no payment or ledger mutation is performed.

The failure produced extra deliveries, not extra captures. That is the useful guarantee.

## 6. Projection versions protect meaning beyond arrival order

`payment_event_projections` contains the latest accepted snapshot, exposed through `PaymentProjectionController` as `PaymentProjectionResponse`. `PaymentEventConsumerRepository` updates it only for a strictly newer `paymentVersion`. A late unique older event still receives a receipt but cannot regress CAPTURED to AUTHORIZED.

A blind last-arrival update is simpler but confuses transport order with business order during replay or delayed delivery. The version is the authority here. This projection contains no accounting side effects: it does not call `PaymentService` to repeat a capture and does not insert ledger entries.

Consequently, GET `/api/v1/payments/{paymentId}/projection` is eventually consistent. Immediately after a successful payment request it may return 404 or an earlier version. Clients needing authoritative payment state use the existing payment endpoint. The projection remains readable from PostgreSQL even if the Kafka listener is currently disabled.

## 7. An explicit JSON contract and explicit failure policy

Kafka keys and values use string codecs. The application parses JSON into `PaymentEvent`, validates schema version `1`, validates snapshot/envelope consistency, and checks that the key equals `paymentId`. The contract does not depend on Java class-name type headers. That keeps the wire format understandable to a future non-Java consumer. Avro/Protobuf with a schema registry would add stronger schema tooling, but also introduce infrastructure and compatibility policies beyond this phase.

Money is bound from the original JSON into `BigDecimal`. Routing a large decimal through a default floating-point JSON tree can lose cents before validation; tests cover `9007199254740992.01` and the maximum supported amount. Required record fields, integer versions, named enum values, and a single JSON document are checked explicitly, so coercion cannot silently repair an invalid event.

Validation errors propagate to a non-skipping retry policy. A malformed or conflicting record blocks progress past that position on its partition until operational correction. This deliberately exposes a broken history rather than silently logging and advancing. Unlimited retry also means a poison record can delay healthy records. Spring's default recoverer or a dead-letter topic could unblock progress, but safe quarantine needs ownership, alerts, ordering decisions, and replay tooling. Those are future work. See [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

The outbox retry limit and consumer retry policy are separate: publication failures can become DEAD in the source database; consumer failures concern a record already accepted by Kafka.

## 8. What verification can prove

Unit tests can check contracts, producer settings, acknowledgement handling, and listener delegation. Real PostgreSQL tests exercise transactions, uniqueness, immutability, rollback, and version-aware projection updates. Mock repositories cannot establish those database guarantees. Broker-backed tests are needed for actual producer/listener/offset behavior; a passing mock-based send test does not establish broker durability or restart recovery.

Read the actual run results and skipped environments in [SESSION_LOG.md](SESSION_LOG.md). Docker-dependent tests are skipped when Docker is unavailable. The optional local PostgreSQL suites require a dedicated disposable database. Single-node development checks cannot establish multi-broker failover, production capacity, security, or a complete operator recovery workflow.

This session passed 168 unit checks, 72 real PostgreSQL checks, and four real Kafka/PostgreSQL checks. The broker tests observe committed offsets and inject failures on both sides of the broker/database boundary. The 78 Docker-dependent methods were skipped; the Compose workflow remains unverified. See the [concise session summary](MILESTONE_5_SUMMARY.md) for the final handoff.

## TOPICS TO LEARN FROM THIS SESSION

- Transactional outbox and the dual-write problem
- Kafka topics, partitions, keys, consumer groups, and offsets
- Producer acknowledgements and producer idempotence
- At-least-once delivery and durable inbox deduplication
- Database constraints and concurrent inserts
- Spring transaction proxies and commit boundaries
- Version-aware projections and eventual consistency
- JSON contracts and schema evolution
- Poison records, retries, dead-letter queues, and replay
- Row locks versus claims and leases
- Broker-backed integration testing
