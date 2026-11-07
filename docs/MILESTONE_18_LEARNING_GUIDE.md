# Milestone 18 learning guide — Verified incoming provider webhooks

Implemented 2026-10-25 in the independent Java `fincore-payments` project. Continue from [Milestone 17](MILESTONE_17_LEARNING_GUIDE.md). Use the [webhook runbook](PROVIDER_WEBHOOK_RUNBOOK.md) for setup/recovery, the [summary](MILESTONE_18_SUMMARY.md) for final verification and the [roadmap](ROADMAP.md) for remaining scope. The Word guide remains the Milestones 1–10 snapshot.

## 1. Why incoming callbacks are the next block

Milestone 17 made an independent sandbox decision recoverable through an unchanged provider operation UUID. If an accepted capture lost its HTTP reply, the merchant could resubmit the original command and retrieve that decision. Recovery still depended on another outbound request.

A webhook lets the provider initiate delivery of its already committed result. That improves the same uncertain-outcome problem without creating a second financial authority. The callback must identify an existing command, authenticate its sender, match frozen intent and commit through the same payment/ledger/outbox rules.

**Example:** the sandbox accepts an authorization but cuts its response short. The payment remains CREATED and the command becomes UNKNOWN. Its signed callback arrives later. The receiver records the event and confirms the original authorization in one database transaction. A later HTTP retry or duplicate callback finds that result and does not authorize again.

This is still a local sandbox. It introduces no live vendor/card account, real money or merchant notification delivery.

## 2. What is now available

- POST `/api/v1/provider-webhooks/sandbox`, protected by raw-body HMAC and a fresh delivery timestamp.
- Durable exact event identity and receipt evidence, with safe duplicate/conflict behavior.
- Confirmation of existing UNKNOWN or IN_FLIGHT commands, including when outbound execution is disabled.
- Late/out-of-order result handling that never rewinds a later payment state or repeats a journal.
- Owned receipt metadata under GET `/api/v1/payments/{paymentId}/provider-operations/{operationId}/webhooks`.
- Optional automatic sandbox callbacks and authenticated redelivery from its retained decision journal, including after process restart.

The incoming receiver and sandbox sender both default off. They use a separate signing secret from the outbound provider API key. Existing M17 behavior remains available with callbacks disabled.

## 3. Three identities that must not be confused

| Identity | Purpose |
| --- | --- |
| Provider operation UUID | Original durable financial command, unchanged across outbound retries and callbacks. |
| Provider event UUID | Logical notification identity, unique within the provider instance; deduplicates receipts. |
| Receipt UUID | Application-generated identifier for the first accepted event body. |

The sandbox derives a stable event UUID from its instance/operation identity. Re-delivery after restart therefore retains the same event/body. The receiver also accepts a distinct event UUID describing the same result: it records ALREADY_RECORDED without applying the operation again.

A delivery timestamp is different from an event or operation identity. It changes when an old logical event is freshly delivered. It establishes freshness of this authenticated delivery, not business ordering. Do not compare delivery timestamps to decide whether to refund, capture or rewind a payment.

## 4. Exact signed protocol

`ProviderWebhook.Event` contains `version`, `eventId` and a nested M17 `ProviderOperation.Response`. The result includes instance, operation, payment, merchant, action, amount, currency, outcome and provider references. No merchant reference, password, card data or arbitrary vendor payload is included.

Three headers are required, exactly once each:

| Header | Value |
| --- | --- |
| `Fincore-Webhook-Key-Id` | Configured nonsecret key ID; default `sandbox-v1`. |
| `Fincore-Webhook-Timestamp` | Unix seconds for this delivery, with canonical positive decimal digits. |
| `Fincore-Webhook-Signature` | `v1=` followed by 64 lowercase hexadecimal characters. |

The signed byte sequence is:

```text
ASCII("fincore-provider-webhook-v1\n" + keyId + "\n" + timestamp + "\n")
    concatenated with the exact raw UTF-8 request body

signature = "v1=" + lowercaseHex(HMAC-SHA256(signingKey, signedBytes))
```

`ProviderWebhookVerifier` checks configuration and headers, reads at most 8 KiB plus one byte, compares the MAC with `MessageDigest.isEqual`, checks freshness again after body verification, then parses the closed protocol. It never parses a reserialized approximation of what was signed.

Whitespace, numeric spelling and field order are part of the signed bytes. Changing them requires a new valid MAC. Reusing an already accepted event identity with changed bytes still conflicts, even when the JSON would have the same meaning.

The default tolerance is inclusive ±300 seconds; configuration supports 30–900 seconds. Rechecking after body reading prevents a delivery that expired during ingestion from being accepted merely because its headers were initially fresh. A fresh retry signs the same event body using a new current delivery timestamp.

HMAC authenticates message integrity using a shared secret; it does not encrypt the payload or provide public-key nonrepudiation. See the primary definition in [RFC 2104](https://www.rfc-editor.org/info/rfc2104/). Anyone holding this signing key can construct a valid MAC, so the key is a trust boundary, not a merchant credential.

## 5. Strict parsing and bounded money validation

`ProviderWebhookWire` reuses the strict versioned JSON reader: invalid UTF-8, duplicate/trailing/unknown fields, missing values and coercion are refused. Event/financial identities must be nonzero UUIDs. The sandbox reference formats and positive `NUMERIC(19,2)` money limits must match the protocol before database access.

Money is checked without expanding an attacker-chosen decimal exponent into a huge integer. `ProviderWire.validAmount` strips trailing zeros and inspects precision/scale using bounded arithmetic. A nonzero third decimal place, oversized integer amount, negative amount or extreme positive/negative exponent is rejected. Trailing zero representations such as `10.000` remain logically exact; frozen command comparison still uses monetary equality.

The receiver verifies the signature before JSON interpretation and all database work. An oversized body is refused before expensive processing. This caps application payload handling; it does not supply production ingress timeouts, rate limiting or denial-of-service protection by itself.

## 6. Authentication is separate from merchant access

`ProviderWebhookSecurityConfiguration` has its own first-priority filter chain matching only the exact callback path. POST reaches the HMAC verifier without merchant Basic credentials or browser CSRF. Other methods are denied. The route does not persist authentication in a session.

Merchant and projection-operator chains retain their previous credentials, scope and CSRF requirements. A valid merchant password does not authenticate a callback. A valid callback MAC does not authorize a merchant payment request. The receipt-list GET still requires actual merchant credentials and source-payment ownership.

This separation follows Spring Security's [multiple filter-chain matching](https://docs.spring.io/spring-security/reference/6.5/servlet/configuration/java.html). Its normal [CSRF protection](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html) remains on browser-credential write surfaces; the exception is deliberately confined to this machine-authenticated route.

The provider instance UUID and one active signing key ID are pinned by receiver configuration. A valid MAC with another instance is refused. This milestone does not add a key ring, overlap rotation policy or live-vendor signature format. Coordinate key changes in the sender and receiver; a new valid key can authenticate a duplicate body without rewriting its original receipt metadata.

## 7. Actual request flow and commit boundary

```text
SandboxProviderStore persists original provider decision
  → SandboxWebhookSender.deliver creates stable event + fresh HMAC headers
  → POST /api/v1/provider-webhooks/sandbox
  → ProviderWebhookController.receive
      → ProviderWebhookVerifier.verify                 [before database work]
      → ProviderWebhookService.accept                 [REQUIRES_NEW, READ_COMMITTED]
          → find matching existing command
          → ProviderWebhookRepository.claim           [unique instance/event]
          → ProviderOperationTransactions.finishWebhook [MANDATORY, same transaction]
              → lock owned source + operation
              → compare frozen intent and existing outcome
              → apply existing payment/ledger/outbox path if eligible
          → ProviderWebhookRepository.finish
          → commit receipt + operation + payment + journal + outbox together
  → ProviderWebhook.Acknowledgement
```

No callback acknowledgement is returned before this transaction commits. There is no separate inbox commit followed by an unimplemented worker. If processing or commit fails, the receipt and financial completion roll back together; the sender must retry.

Claim insertion uses PostgreSQL uniqueness with `ON CONFLICT DO NOTHING`. A concurrent duplicate waits for the first insertion to commit or roll back. After commit, it reads the saved exact body/disposition. If the first transaction rolls back, the contender can become the owner. Source and operation locks use NOWAIT so competing financial work produces a retryable failure rather than retaining a partial receipt.

The callback performs no outbound provider call inside its transaction. `REQUIRES_NEW` gives it a fresh commit even if a trusted internal caller has an older ambient transaction. The existing M17 orchestrator still suspends active participation around provider HTTP work.

## 8. Receipt dispositions and HTTP acknowledgement

| Disposition | Meaning |
| --- | --- |
| CONFIRMED | Existing dispatched command was confirmed and its documented local effect committed; the provider outcome may still be a decline. |
| ALREADY_RECORDED | Same confirmed result already exists; no additional financial/state application. |
| REQUIRES_REVIEW | Confirmed result is retained, but frozen source consistency failed; no forced financial application. |
| UNMATCHED | No command matching the claimed merchant/payment/instance identity exists; no payment is created or adopted. |
| REJECTED | Known intent differs, outcome contradicts history, or command was never dispatched. |

HTTP 200 means a verified event has a durable final receipt. It does not mean the payment was approved, captured or automatically repaired. The acknowledgement contains receipt/event UUIDs, disposition and `duplicate` only. Inspect the operation's provider outcome/current payment and the owned receipt metadata when diagnosing it.

An exact duplicate returns its original receipt/disposition with `duplicate:true`. A fresh event describing an old confirmed result gets its own ALREADY_RECORDED receipt. A changed body under the same instance/event UUID gets 409 and preserves the first receipt unchanged.

UNMATCHED and REJECTED receipts are acknowledged for diagnosis, not queued for later automatic repair. Replaying the same body returns the original disposition. There is no API to relink, rewrite, force-apply or reprocess them. Unknown commands are impossible for the normal legitimate flow because intent commits before the provider request; an unmatched event indicates a namespace/history/configuration problem requiring investigation.

## 9. Safe application, races and ordering

### A callback after UNKNOWN

V18 permits UNKNOWN → APPLIED/DECLINED/REQUIRES_REVIEW only when a matching receipt is being processed in the same PostgreSQL transaction. It does not manufacture another outbound claim, consume the retry budget, or contact the provider again. Outbound integration may be disabled while this separately enabled, correctly signed receiver confirms an existing command.

### A callback during IN_FLIGHT

The callback can complete the current command under the ordinary payment/operation locks. Completion clears its lease. When the HTTP worker later returns, `finish` finds a terminal state and returns it without a second effect. A late `uncertain` call also leaves that result intact. The callback and synchronous response must agree with frozen intent; a contradictory terminal notification is rejected rather than overwriting history.

### A callback for an older transition

After a payment is REFUNDED, an original AUTHORIZE or CAPTURE confirmation may arrive. The corresponding command already contains its terminal result, so the callback is recorded as ALREADY_RECORDED. It does not compare delivery timestamps to move the payment backwards. Integration tests deliver refund, capture and authorization notifications in reverse order and retain one capture plus one reversal.

### A callback for READY

A merely prepared command has never been claimed for dispatch. It is rejected with NOT_DISPATCHED. A signed message cannot create a payment, attach an unrelated source or bypass command enrollment. Provider-generated scenarios are still local sandbox test controls, never authority for a live-vendor approval.

### Source mismatch or local failure

Frozen source version/status/amount/currency still gate application. Detected drift retains REQUIRES_REVIEW without a journal. If the outbox or receipt finalization fails after financial work, the whole transaction rolls back. Retrying the same signed event with fresh delivery headers can then complete once; an HTTP failure must not be interpreted as a provider decline.

## 10. V18 schema and local evidence guarantees

`provider_webhook_receipts` stores exact bounded body bytes and SHA-256, normalized result identities/money/references, first accepted key ID/delivery timestamp, receipt time, matched operation and final disposition/reason. The secret and request MAC are not retained. Public metadata omits raw bodies and credentials.

Database checks bind normalized columns to the raw JSON and digest. A matched operation must agree with merchant, payment and provider instance. A receipt starts RECEIVED and can be finalized once in its insertion transaction; a deferred constraint forbids committing an unfinished receipt. Completed receipts reject UPDATE/DELETE.

The new UNKNOWN completion branch requires a matching RECEIVED row with the same result and current transaction identity. Existing V17 intent immutability, attempt/lease transitions, terminal history, managed-payment guards and financial/outbox checks remain. Earlier migration files are unchanged. Upgrade tests preserve M17 merchant/payment/retry/outbox/binding/command before-images and start with no receipts.

These controls provide consistent local audit evidence. They are not independent proof that a historical MAC was checked, external storage attestation or protection against a privileged database administrator. The trusted verifier/application/database remain part of the boundary; no RLS or cryptographic database ledger is introduced.

## 11. Sandbox delivery and recovery mechanics

The standalone `provider-sandbox` entry point accepts optional callback URL/key/key-ID environment settings. When configured, it queues a callback after a decision has been persisted, including when the original response is incomplete. Provider processing does not roll back when delivery fails.

The sender has one worker and a bounded queue of 64 notifications. It uses the shared direct HTTP transport: loopback receiver path only, no proxy/redirect, two-second complete-response deadline and 8 KiB acknowledgement cap. Acknowledgement identity and shape are validated. Queue rejection or an unconfirmed request emits a sanitized event-ID diagnostic and retains the provider decision.

This queue is in memory and has no retry scheduler or crash scan. The durable decision journal is the source for explicit redelivery. POST `/v1/webhooks/{operationId}/deliver` on the sandbox requires its existing provider API Bearer key, an empty body and a known persisted decision. It signs that decision with a current delivery timestamp; callers cannot supply an invented result body.

After restart with the original directory/instance, this endpoint can redeliver the same event/body without sending another payment command. The packaged-JAR test forcibly kills the sandbox process, restarts it, and verifies that the signed body remains identical. The actual servlet-receiver test also exercises an accepted-but-lost HTTP reply followed by a callback into the running Spring Boot application.

Retaining the journal does not certify sudden power-loss durability or independent backup/restore. Sandbox delivery is a development aid, not Milestone 19's reliable outgoing merchant-notification service.

## 12. Alternatives and tradeoffs

| Chosen approach | Alternative and why it was not chosen here |
| --- | --- |
| HMAC over exact bytes plus delivery timestamp | A static bearer token alone does not bind the body or freshness. HMAC requires careful byte framing, clock/key management and shared-secret trust. |
| Dedicated callback security chain | Disabling CSRF across payment APIs would weaken browser-credential protection. One exact machine route keeps boundaries explicit. |
| One transaction for receipt and application | Separately acknowledging an inbox before processing requires a durable worker/recovery contract. Synchronous processing is simpler here but latency/locks can cause retryable failures. |
| Unique instance/event plus exact-body comparison | In-memory deduplication disappears on restart. Event identity alone would hide changed payloads. Persistent evidence consumes storage and needs future retention planning. |
| Existing frozen command and terminal-outcome checks | Trusting only a payment ID or delivery timestamp could adopt another payment or rewind history. Commands make authority narrower but reject unsupported unsolicited events. |
| Shared confirmed-result financial path | Callback-specific posting code could drift from synchronous capture/refund accounting. Reuse keeps invariants together and requires explicit transaction guards. |
| Durable rejected/unmatched dispositions | Silently dropping them loses diagnosis; repeatedly returning failure for a permanent mismatch creates retry storms. Acknowledged evidence needs later controlled investigation rather than automatic repair. |
| Bounded best-effort sandbox queue plus explicit redelivery | A fully durable delivery worker would add retry/backoff/claim/retention operations to a test provider. That larger contract belongs to a dedicated delivery milestone; journal-backed manual recovery makes this limit visible. |
| Separate incoming and outgoing webhook milestones | The provider test sender is enough to exercise ingestion. Real merchant notifications need subscriptions, destination validation, per-tenant secrets, delivery attempts and operational recovery. |

These alternatives are contextual. A live vendor may require asymmetric signatures, key discovery, different event semantics or asynchronous inbox workers. This implementation intentionally implements one local, documented protocol first.

## 13. Important files

| Project-relative file | Purpose |
| --- | --- |
| `src/main/java/com/fincore/payments/controller/ProviderWebhookController.java` | Machine receiver and merchant-owned receipt metadata list. |
| `src/main/java/com/fincore/payments/provider/ProviderWebhookVerifier.java` | Raw-body MAC, header, timestamp and instance verification before database work. |
| `src/main/java/com/fincore/payments/provider/ProviderWebhookWire.java` | Closed event contract, deterministic sandbox event identity and HMAC framing. |
| `src/main/java/com/fincore/payments/service/ProviderWebhookService.java` | Atomic receipt claim, deduplication and application coordination. |
| `src/main/java/com/fincore/payments/repository/ProviderWebhookRepository.java` | Exact body/evidence persistence and bounded owned metadata. |
| `src/main/java/com/fincore/payments/service/ProviderOperationTransactions.java` | Shared synchronous/callback completion, frozen intent and late-result handling. |
| `src/main/java/com/fincore/payments/config/ProviderWebhookSecurityConfiguration.java` | Separate exact-path machine security boundary. |
| `src/main/java/com/fincore/payments/config/ProviderWebhookProperties.java` | Disabled-by-default receiver, key/instance and freshness configuration. |
| `src/main/java/com/fincore/payments/provider/SandboxWebhookSender.java` | Optional local automatic callback and explicit redelivery transport. |
| `src/main/java/com/fincore/payments/provider/BoundedProviderHttp.java` | Shared capped/deadline HTTP transport for operations and callbacks. |
| `src/main/java/com/fincore/payments/provider/SandboxProviderServer.java` | Queue notifications after acceptance and expose authenticated stored-decision redelivery. |
| `src/main/resources/db/migration/V18__add_verified_provider_webhook_receipts.sql` | Durable receipt constraints and narrowly guarded UNKNOWN confirmation. |
| `src/test/java/com/fincore/payments/integration/ProviderWebhookDatabaseTests.java` | Real merchant, SQL, servlet callback, concurrency, upgrade and rollback evidence. |
| `src/test/java/com/fincore/payments/operations/PackagedProviderSandboxIT.java` | Packaged process restart and identical signed-event redelivery. |

The headless event CLI explicitly disables receiver requirements even when API environment variables enable webhooks. Basic property parsing/timing validation still apply. Existing test resets include receipt children; previous financial assertions were preserved.

## 14. Validation, limits and holistic impact

The [summary](MILESTONE_18_SUMMARY.md) records the final full-run counts, corrections and environment limits. Tests cover malformed/authentication failures without receipt writes, exact and distinct-event duplicates, contradictory results, reverse ordering, unknown/foreign identity, READY refusal, active-worker completion, rollback, lock contention, owned metadata, disabled configurations, review state and V17 upgrade preservation. An independent Python HMAC fixture checks the documented framing. Extreme decimal exponents are refused without large rescaling.

The platform now has two confirmation paths into the same financial boundary: an outbound HTTP result and an authenticated callback. Either can finish first; both must agree with the original command. Existing outbox/Kafka delivery and projection recovery continue to consume the resulting durable payment events. They do not independently approve provider outcomes.

No live vendor, automatic receipt reprocessing, permanent-mismatch repair tool, receiver key ring, rate/risk control, production ingress hardening, partial/void operation, settlement or production readiness is claimed. Sandbox callback queue recovery is manual and journal-backed. Trusted database access remains privileged.

**Next logical milestone: 19 — reliable outgoing merchant webhooks.** This needs owned subscriptions, validated destinations, signed merchant notifications, durable delivery identities/attempts, retry/backoff and observable failure. Incoming provider verification does not implement that outbound business service. Approximately seven proposed blocks remain, 19–25; use the [finish criteria](ROADMAP.md#finish-criteria) rather than milestone numbers as a production-readiness measure.

## TOPICS TO LEARN FROM THIS SESSION

- Webhooks and asynchronous confirmation
- HMAC-SHA256 and shared-secret trust
- Raw-body signatures, freshness and replay windows
- Strict UTF-8/JSON and bounded numeric validation
- Dedicated Spring Security filter chains and CSRF boundaries
- Durable inbox identities and exact-body deduplication
- PostgreSQL uniqueness, locks and deferred constraints
- Atomic receipt/payment/ledger/outbox transactions
- Out-of-order delivery and terminal-state protection
- Callback versus HTTP completion races
- Bounded queues, best-effort delivery and redelivery
- Fault injection, migration and executable-process testing
