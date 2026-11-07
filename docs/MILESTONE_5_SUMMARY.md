# Milestone 5 — Session summary

Completed 2026-10-22. Project: **fincore-payments — Distributed Payment Processing Platform**.

## 1. WHAT WAS IMPLEMENTED

- Optional Kafka transport for committed outbox events, with broker acknowledgement before marking delivery.
- Payment-keyed JSON messages with schema and snapshot validation that preserves exact money values.
- Durable consumer receipts that make duplicate delivery harmless and reject conflicting identities/content.
- Latest-payment projections that accept newer versions and resist stale or concurrent updates.
- GET `/api/v1/payments/{paymentId}/projection`, with structured 404 before a projection exists.
- Optional single-node Kafka Compose profile; the existing local transport remains the default.

## 2. CONCEPTS / TECHNOLOGIES USED

- Spring Kafka — producer, listener, partitions, keys, consumer groups, and offsets.
- At-least-once delivery and idempotency — repeated messages produce one durable consumer effect.
- PostgreSQL/JDBC and `ON CONFLICT` — concurrent receipt claims and conditional projection upserts.
- `@Transactional` / READ COMMITTED — receipt and projection commit together before offset acknowledgement.
- Flyway V5 — independent consumer schema and immutable receipt history.
- JUnit, Mockito, MockMvc, Testcontainers — boundary and real-service integration tests.

## 3. IMPORTANT FILES

- `KafkaEventConfiguration.java` — producer guarantees, codecs, listener acknowledgements, and retry policy.
- `KafkaPaymentEventPublisher.java` — sends payment-keyed envelopes and waits for broker acknowledgement.
- `KafkaPaymentEventListener.java` — validates the wire message and invokes transactional consumption.
- `PaymentEventConsumer.java` / `PaymentEventConsumerRepository.java` — durable deduplication and version-aware projection writes.
- `PaymentProjectionController.java` — exposes the projection read endpoint.
- `V5__create_payment_event_consumer.sql` — creates consumer receipts, constraints, immutability, and projections.
- `docker-compose.yml` / `application.yml` — optional Kafka runtime and configuration.
- `KafkaEventDatabaseTests.java` / `KafkaBrokerTests.java` — concurrency, rollback, duplicate, and offset checks.
- `README.md` / `SESSION_LOG.md` / `MILESTONE_5_LEARNING_GUIDE.md` — setup, results, explanations, and alternatives.

## 4. REQUEST FLOW

```text
POST /api/v1/payments/{paymentId}/capture
→ PaymentController.capturePayment → PaymentService.capturePayment
→ payment + ledger + outbox COMMIT
→ OutboxDispatcher.dispatchBatch → OutboxDeliveryWorker.dispatchNext
→ KafkaPaymentEventPublisher.publish → Kafka
→ KafkaPaymentEventListener.onMessage → PaymentEventConsumer.consume
→ consumed_payment_events + payment_event_projections COMMIT → offset commit
```

The source worker commits DELIVERED after broker acknowledgement, independently of consumer progress.

## 5. IMPORTANT DESIGN DECISIONS

- Reuse the outbox: direct database/broker dual writes leave an untracked failure window.
- Use database receipts: an in-memory set cannot survive restarts or arbitrate multiple instances.
- Commit consumer data before offsets: a crash may repeat work safely instead of skipping unapplied work.
- Guard projection updates by payment version: arrival order alone can regress state during replay.
- Kafka supports the replayable-stream learning goal; RabbitMQ remains valid for different routing/queue requirements.
- Keep bounded publication under the row lock for simple ownership; leases would add expiry and fencing logic.
- Retry failed records without skipping; quarantine and operator recovery remain a separate milestone.

Detailed impact and contextual alternatives: [learning guide](MILESTONE_5_LEARNING_GUIDE.md).

## 6. TEST STATUS

- **168** unit/service/configuration/MVC checks passed; Java 21 application compiled and packaged.
- **72** real PostgreSQL checks passed, including all earlier idempotency/ledger/outbox regressions and 14 consumer checks.
- **4** real Kafka/PostgreSQL checks passed: lifecycle delivery, duplicates, source acknowledgement rollback, and consumer rollback/offset retry.
- One initial test-only exception-type assertion was corrected; no failures remain.
- **78 Docker-dependent methods skipped** because Docker is unavailable. Compose/PostgreSQL 17.6 execution and multi-broker failover remain unverified. Local PostgreSQL was 18.6, newer than Flyway's tested support; native Kafka was 3.9.1.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ milestones 1–5: payment lifecycle, idempotency, ledger, outbox, Kafka/projection.

**CURRENTLY POSSIBLE:** ✓ run payment operations and observe durable asynchronous projections with safe duplicate handling.

**NOT IMPLEMENTED YET:** ✗ dead-letter/repair/replay tooling, retention, separate consumer deployment, authentication, production Kafka operations, or actual money movement.

**NEXT LOGICAL MILESTONE:** event operations—failure/lag visibility, poison-record handling, and safe outbox replay.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Transactional outbox and dual writes
- Kafka partitions, keys, groups, and offsets
- Producer idempotence versus consumer idempotency
- At-least-once delivery and crash windows
- Database uniqueness and concurrent `ON CONFLICT`
- Spring transaction proxies and acknowledgement order
- Version-aware projections and eventual consistency
- Exact decimal JSON parsing and schema validation
- Retry, dead-letter, and replay policies
- Real-broker integration testing
