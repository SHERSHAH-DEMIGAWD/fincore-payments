# Milestone 19 learning guide — Reliable outgoing merchant webhooks

Implemented 2026-10-26 in the independent Java `fincore-payments` project. Continue from [Milestone 18](MILESTONE_18_LEARNING_GUIDE.md). See the [merchant webhook runbook](MERCHANT_WEBHOOK_RUNBOOK.md), [summary](MILESTONE_19_SUMMARY.md), and [roadmap](ROADMAP.md). The Word guide remains the Milestones 1–10 snapshot.

## 1. Why merchant callbacks are a separate block

Milestone 18 receives authenticated provider results. Merchants also need to learn when their payments change without polling. This block publishes a merchant-owned notification for each new payment outbox event. It is deliberately separate from the provider callback sender: the merchant destination, signing identity, delivery records, retries, and access boundaries are different.

The target is an end-to-end local learning flow. Destinations are loopback-only, delivery is opt-in, and no public Internet vendor or real money is involved.

## 2. What is available

- Authenticated merchant endpoints create, list, pause, and resume webhook subscriptions.
- Each subscription has a generated signing secret returned at creation and on an exact accepted create replay. The application stores it encrypted, not as plaintext.
- A PostgreSQL trigger enrolls every new payment outbox event for active subscriptions in the same transaction as its payment change.
- Delivery requests carry the exact immutable stored JSON body and an HMAC-SHA256 signature with a fresh timestamp.
- A scheduled worker uses bounded leases, HTTP deadlines, retries with capped exponential backoff, attempt history, and a terminal `DEAD` state.
- Merchants can inspect their own delivery/attempt metadata and explicitly retry a dead delivery with a revision-like retry generation and reason.
- A runnable local sink verifies signatures, durably deduplicates delivery IDs, and supports a repeat demo after process restart.

Subscriptions created after an event do not receive that historical event. A paused subscription does not enroll new events; already-enrolled events remain stored and are blocked while paused.

## 3. Event and delivery identity

The immutable `PaymentEvent.eventId` identifies the original payment fact. Each enrolled subscription gets its own `deliveryId`. The stored body includes both IDs, the subscription ID, and a payment event snapshot. Reattempts preserve the same delivery ID and exact bytes while the timestamp/signature change for each HTTP attempt.

The receiver can safely return a success response after it has durably accepted that delivery ID. If it stores the event but its response is lost, the sender sees an unconfirmed attempt and repeats it. The receiver recognizes the same delivery/body rather than performing the side effect twice. This is **at-least-once transport with receiver-side idempotency**, not magical exactly-once networking.

## 4. Signature protocol and encrypted secret

The receiver gets one each of `Fincore-Webhook-Key-Id`, `Fincore-Webhook-Timestamp`, and `Fincore-Webhook-Signature`. The key ID is the subscription UUID. The signed input is:

```text
ASCII("fincore-merchant-webhook-v1\n" + keyId + "\n" + timestamp + "\n")
|| exact stored body bytes
```

`MerchantWebhookCrypto.signature` creates `v1=` plus lowercase HMAC-SHA256 hex. The sink checks the exact body and an inclusive ±300-second freshness window. A shared secret proves that the sender knows the signing key; it does not encrypt the payload or prove public-key nonrepudiation.

The 32-byte master key encrypts subscription secrets using AES-256-GCM. Authenticated additional data binds ciphertext to the master-key ID, merchant and subscription, so moving ciphertext between tenants/subscriptions fails authentication. One active master key ID is supported. Rotation is not implemented: changing the key ID makes old subscriptions unavailable until a deliberate re-encryption/rotation facility is designed. A production deployment should consider a managed KMS/HSM and explicit staged key rotation.

## 5. Transaction boundaries and delivery ordering

The V19 database trigger observes each inserted `payment_outbox` row and snapshots a delivery for every active subscription belonging to the payment owner. Because this happens in the source transaction, a committed payment event cannot be missing its enrolled notification, and a rolled-back event cannot leave an orphan delivery.

`MerchantWebhookRepository.candidate` claims one eligible delivery using PostgreSQL row locks and `SKIP LOCKED`. `MerchantWebhookTransactions.claimNext` writes an attempt and a fresh lease token in a short transaction. `MerchantWebhookDispatcher` then calls `MerchantWebhookHttpClient` after that transaction has ended. A second short transaction records the response only if the same token still owns the lease. Expired workers cannot overwrite a newer claim. Expired attempts are closed as `ABANDONED` before reclaiming.

For a given subscription and payment, a later payment version waits while any earlier delivery is not `DELIVERED`. A `DEAD` earlier delivery blocks successors until an operator retries it. This preserves merchant-visible order at the cost of per-payment head-of-line blocking; unrelated subscriptions/payments can progress concurrently.

## 6. Retry, failure, and manual recovery

Only a complete 2xx response acknowledges delivery. Non-2xx responses, timeouts, oversized acknowledgements, and connection failures remain unconfirmed. The immutable body and delivery ID are retained. Retries use a bounded cycle budget and exponential delay capped by configuration; exhaustion changes the delivery to `DEAD` and records the last error code/status.

`POST /api/v1/merchant/webhooks/deliveries/{deliveryId}/retry` requires the current retry generation, a unique request UUID, and a reason. The delivery returns to `PENDING` in the same transaction as an immutable retry audit. Exact retries replay the saved decision; changed intent conflicts. A manual retry starts a new cycle but does not erase older attempts.

An HTTP timeout does not mean the merchant failed to process the event: the receiver may have committed before its acknowledgement disappeared. Therefore retries always reuse the exact body and ID. The local sink records the delivery before sending 204 and returns a conflict for the same ID with different bytes.

## 7. Tenant and network boundaries

The management API lives under `/api/v1/merchant/webhooks` and uses the existing merchant Basic principal and CSRF rules. Repository queries scope IDs by authenticated merchant; a foreign subscription/payment/delivery is reported as not found. Responses expose safe state, hashes, timestamps and attempt metadata, never payload bytes, encrypted secret bytes, plaintext secret or response body.

For this local sandbox, `MerchantWebhookDestinationPolicy` accepts canonical literal `127.0.0.1` or `[::1]` HTTP(S) URLs and the worker uses no proxy and follows no redirects. Literal loopback avoids DNS rebinding at this boundary. A production callback service would need a separately reviewed egress-control design; simply allowing arbitrary hostnames would create SSRF and internal-network access risk.

## 8. Database-enforced evidence

`V19__add_reliable_merchant_webhooks.sql` adds subscriptions, immutable state-change audits, deliveries, attempts, and manual retry audits. The trigger captures the exact new outbox snapshot. Checks bind each stored SHA-256 to its body, enforce bounded body/counter/state shapes, and deferred commit checks require delivery counters and latest attempt state to agree. Immutable identity columns and audit rows reject ordinary update/delete; lease and terminal transitions are constrained in PostgreSQL as well as Java.

Validation migrated both an empty schema through V1–V19 and a separate clone from V18 to V19. The upgrade preserved its preexisting payment, outbox event and merchant counts; earlier migration files were not edited.

Application checks provide understandable API errors; database constraints protect the evidence even if a different application path writes SQL. They are not a defense against a superuser who can alter schema/functions, nor a substitute for backup and retention controls.

## 9. Alternatives and tradeoffs

| Chosen approach | Alternative and why it was not chosen here |
| --- | --- |
| Enroll from the PostgreSQL outbox insert trigger | A later application callback or poll could crash after the payment commits but before enrollment. A trigger makes enrollment part of the same database transaction, but couples the schema to the v1 event envelope. |
| Persist exact body, IDs, hashes and attempt history in PostgreSQL | Rebuilding bodies on every retry risks event drift; an in-memory queue loses work on restart. Durable rows cost storage and still require future retention/archival policy. |
| Network call outside a database transaction, with lease token fencing | Holding row locks during HTTP spends database connections on slow/unavailable merchants. The lease allows crash recovery; fencing prevents an old worker from recording over a later claim, at the cost of duplicate sends after uncertain outcomes. |
| At-least-once delivery plus stable ID and receiver deduplication | Exactly-once side effects across PostgreSQL and arbitrary HTTP receivers are not available as one atomic transaction. The receiver must deduplicate; a 2xx acknowledgement alone cannot prove its downstream work is exactly once. |
| HMAC per subscription, with AES-GCM encryption at rest | Plaintext storage exposes keys in database snapshots. A static global bearer secret has weak tenant isolation and body binding. KMS would improve key custody/rotation but adds external infrastructure beyond this local block. |
| Literal loopback allowlist, no proxy or redirect | Arbitrary hostnames/redirects are convenient but risk SSRF and policy bypass. The strict local restriction intentionally limits this milestone; production needs controlled egress, not a relaxed regex. |
| Bounded exponential retries, then audited `DEAD` plus explicit merchant retry | Infinite retries can monopolize resources and hide a permanently broken receiver. Automatic forever-retry is simpler operationally but removes an explicit recovery decision and bounded workload. |
| Preserve per-payment delivery order | Unordered parallel events improve throughput but let a merchant observe `CAPTURED` before `AUTHORIZED`. Ordering can head-of-line block one payment; unrelated payment streams remain concurrent. |

## 10. Holistic impact and limits

Payments now notify both internal event consumers and external merchant receivers from durable, independently tracked paths. A payment/outbox transaction atomically creates the merchant delivery record; worker retries cannot alter financial state. Merchant access includes subscription controls and delivery evidence while provider callbacks remain a separate machine-authenticated path.

This is still a learning sandbox. There is no multi-key rotation, webhook deletion/retention executor, arbitrary public endpoint, production egress firewall, distributed metrics/alerts, or guarantee of the receiving merchant's downstream effect. The existing body cap and direct HTTP timeout limit application work but do not provide production ingress or capacity protection. Continue with Milestone 20: partial operations and explicit fee accounting.

## 11. Main code path

```text
PaymentService transition
→ payment_outbox INSERT
→ V19 enroll_merchant_webhooks trigger
→ MerchantWebhookRepository.candidate
→ MerchantWebhookTransactions.claimNext (short DB transaction)
→ MerchantWebhookDispatcher.dispatchBatch
→ MerchantWebhookHttpClient.send (no DB transaction)
→ MerchantWebhookTransactions.finish (lease-token check, short DB transaction)
→ merchant delivery + attempt history
```

## TOPICS TO LEARN FROM THIS SESSION

- Webhook delivery semantics and at-least-once processing
- HMAC-SHA256, exact-body signing and timestamp replay windows
- AES-GCM authenticated encryption and key rotation
- Transactional outbox enrollment with PostgreSQL triggers
- `FOR UPDATE SKIP LOCKED`, leases and fencing tokens
- Database transactions around network calls
- Exponential backoff, retry generations and dead-letter recovery
- Idempotent receivers and duplicate delivery handling
- Tenant-scoped authorization and CSRF
- SSRF, destination allowlists and egress controls
- Deferred PostgreSQL constraints and immutable audit history
- Head-of-line blocking and per-aggregate event ordering
