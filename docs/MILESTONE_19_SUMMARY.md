# Milestone 19 — Reliable outgoing merchant webhooks

Implemented 2026-10-26. [Learning guide](MILESTONE_19_LEARNING_GUIDE.md) · [Runbook](MERCHANT_WEBHOOK_RUNBOOK.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Merchant-owned subscription create/list/pause/resume endpoints with request replay protection.
- Encrypted per-subscription HMAC signing secrets and strict loopback destination allowlists.
- Same-transaction enrollment of future payment outbox events into durable per-merchant deliveries.
- Bounded HTTP signing, response deadlines, direct connections and disabled redirects/proxies.
- Database-backed delivery leases, fenced workers, attempt history and capped exponential retries.
- Per-payment ordering, terminal DEAD state and reasoned, idempotent manual retries.
- Tenant-scoped delivery and attempt inspection with safe metadata.
- Standalone durable local merchant webhook receiver for demonstration and duplicate handling.

## 2. Concepts / technologies used

- Spring MVC / Bean Validation — merchant subscription and recovery API.
- HMAC-SHA256 / exact-body signatures — authenticated notifications.
- AES-256-GCM — encrypted subscription secrets bound to tenant/subscription identity.
- PostgreSQL trigger / transactional outbox enrollment — delivery intent committed with source event.
- JDBC / `SKIP LOCKED` / leases / fencing tokens — concurrent durable worker claims.
- At-least-once delivery / idempotent receiver — safe network retries.
- Immutable audit rows / deferred constraints — consistent attempt and retry evidence.
- JUnit 5 / MockMvc / real PostgreSQL / local HTTP — protocol, migration and delivery checks.

## 3. Important files

- `controller/MerchantWebhookController.java` — authenticated merchant webhook endpoints.
- `service/MerchantWebhookService.java` — subscription, ownership, inspection and retry rules.
- `service/MerchantWebhookTransactions.java` / `MerchantWebhookDispatcher.java` — leased DB state transitions and out-of-transaction network delivery.
- `repository/MerchantWebhookRepository.java` — tenant-scoped SQL, claim, attempt, retry and audit persistence.
- `merchantwebhook/MerchantWebhookCrypto.java` / `MerchantWebhookHttpClient.java` / `MerchantWebhookDestinationPolicy.java` — secret protection, signatures, bounded transport and SSRF boundary.
- `merchantwebhook/MerchantWebhookSink.java` / `MerchantWebhookSinkMain.java` — durable local demo receiver.
- `db/migration/V19__add_reliable_merchant_webhooks.sql` — subscription, delivery, attempt, enrollment, audit and database transition rules.
- `docs/MILESTONE_19_LEARNING_GUIDE.md` / `MERCHANT_WEBHOOK_RUNBOOK.md` — rationale, alternatives, operational demo and recovery.
- `src/test/java/com/fincore/payments/integration/MerchantWebhookDatabaseTests.java` — real-PostgreSQL merchant delivery/retry tests.

## 4. Request flow

```text
POST /api/v1/merchant/webhooks/subscriptions
→ MerchantWebhookController.create
→ MerchantWebhookService.create
→ MerchantWebhookRepository.insertSubscription

PaymentService.authorizePayment
→ payment_outbox INSERT
→ enroll_merchant_webhooks trigger
→ MerchantWebhookRepository.candidate
→ MerchantWebhookTransactions.claimNext
→ MerchantWebhookDispatcher.dispatchBatch
→ MerchantWebhookHttpClient.send (outside DB transaction)
→ MerchantWebhookTransactions.finish (lease token check)
```

## 5. Important design decisions

- Database-triggered enrollment keeps a committed payment event and its delivery intents atomic.
- Exact body and delivery identity persist across attempts; each attempt gets a fresh timestamp/signature.
- A 2xx only means the receiver acknowledged; receiver-side deduplication handles lost responses.
- Network calls occur outside database transactions; lease tokens prevent stale workers from completing newer claims.
- Earlier unconfirmed versions block later notifications for the same payment/subscription.
- Loopback-only URL allowlisting is a sandbox SSRF boundary; public egress needs a separate design.
- AES-GCM protects signing keys at rest; one active key is supported and safe key rotation is not implemented.

## 6. Test status

- Focused crypto/destination/HTTP tests: **57 passed**, zero failures/errors (included in the full unit total).
- Full Java 21 Maven unit/MVC/service/configuration regression: **646 passed**, zero failures/errors.
- Fresh PostgreSQL 18.6 V1–V19 migration plus actual API/HTTP/delivery/retry/lease-fencing integration: **4 passed**, zero failures/errors.
- An isolated clone at schema V18 upgraded to V19; before/after counts remained 1 payment, 1 outbox event and 7 merchants.
- Full Kafka, Docker/PostgreSQL 17.6, packaged full-project verification, load, Linux, public ingress, key rotation and multi-instance behavior were not run in this milestone. Flyway warns PostgreSQL 18 exceeds its currently tested support range.
- Build commands ran on Java 21. A targeted standalone PostgreSQL test database and workspace cluster were used; unrelated PostgreSQL 5432/project were untouched.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–19, including reliable outgoing merchant webhook delivery in the bounded local sandbox.

**CURRENTLY POSSIBLE:** ✓ Create a merchant-owned local callback subscription, receive signed payment snapshots, inspect attempts, retry a dead delivery and deduplicate exact repeats in the local sink.

**NOT IMPLEMENTED YET:** ✗ Partial capture/refund and fees, settlement/external reconciliation, Redis/risk, full observability, independent backup/restore/retention closure, staged key rotation, public egress, and deployment/final validation. No live vendor or funds.

**NEXT LOGICAL MILESTONE:** 20 — partial payment operations and explicit fee accounting. Six proposed blocks remain, 20–25.

## 8. TOPICS TO LEARN FROM THIS SESSION

- At-least-once delivery and receiver idempotency
- HMAC signing and replay windows
- AES-GCM and key rotation
- Transactional outbox enrollment
- PostgreSQL triggers, row locks and `SKIP LOCKED`
- Lease fencing and crash recovery
- Exponential backoff and dead-letter retry
- Tenant-scoped API authorization
- SSRF and egress controls
- Deferred constraints and immutable audit records
- Per-payment event ordering and head-of-line blocking
