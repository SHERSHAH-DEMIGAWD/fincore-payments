# Milestone 18 — Verified incoming provider webhooks

Implemented 2026-10-25. [Learning guide](MILESTONE_18_LEARNING_GUIDE.md) · [Webhook runbook](PROVIDER_WEBHOOK_RUNBOOK.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Incoming sandbox callbacks authenticated over exact body bytes with HMAC-SHA256 and delivery freshness.
- Durable event receipts with exact duplicates, changed-body conflicts and immutable evidence.
- Safe confirmation of existing UNKNOWN/IN_FLIGHT provider commands through the established financial path.
- Late/out-of-order and contradictory result handling without repeated journals or backward transitions.
- Merchant-owned, bounded receipt metadata reads.
- Optional sandbox callback sending and stored-decision redelivery after process restart.
- Separate callback security boundary preserving merchant/operator credentials and CSRF.

## 2. Concepts / technologies used

- HMAC-SHA256 / timestamp windows / strict JSON — authenticate bounded exact payloads before database access.
- Durable inbox / PostgreSQL uniqueness — receipt identity and repeat delivery.
- Spring transaction propagation / row locks — receipt and financial completion commit together.
- Deferred constraints / immutable evidence — prevent unfinished or inconsistent receipt commits.
- Bounded HTTP / worker queue — optional sandbox delivery and explicit redelivery.
- JUnit / Mockito / real PostgreSQL / servlet HTTP / packaged processes — races, rollback and restart evidence.

## 3. Important files

- `ProviderWebhookController.java` / `ProviderWebhookVerifier.java` — receive and verify machine callbacks; expose owned metadata.
- `ProviderWebhookService.java` / `ProviderWebhookRepository.java` — atomic durable receipt handling and deduplication.
- `ProviderOperationTransactions.java` — shared confirmed-result application and late-result protection.
- `ProviderWebhookWire.java` / `ProviderWebhookProperties.java` / `ProviderWebhookSecurityConfiguration.java` — protocol and dedicated security configuration.
- `SandboxWebhookSender.java` / `SandboxProviderServer.java` / `BoundedProviderHttp.java` — local automatic delivery, redelivery and transport bounds.
- `V18__add_verified_provider_webhook_receipts.sql` — receipt invariants and guarded UNKNOWN completion.
- Learning guide / runbook / new callback tests — teaching, runnable recovery and verification.

## 4. Request flow

```text
POST /api/v1/provider-webhooks/sandbox + HMAC headers
→ ProviderWebhookController.receive → ProviderWebhookVerifier.verify
→ ProviderWebhookService.accept → ProviderWebhookRepository.claim
→ ProviderOperationTransactions.finishWebhook → PaymentService.applyProviderResult
→ receipt + provider command + payment + ledger + outbox commit
→ ProviderWebhook.Acknowledgement
```

## 5. Important design decisions

- Authenticate exact bytes before JSON/database work; use fresh headers for redelivery of the same event.
- Commit receipt and documented financial effects in one transaction before acknowledging.
- Match existing frozen command identity; never create/adopt a payment from a callback.
- Preserve terminal outcomes; late matching events cannot rewind payment state.
- Keep callback authentication independent from merchant Basic/CSRF and outbound enablement.
- Keep sandbox automatic delivery bounded and best effort, with journal-backed explicit redelivery.

## 6. Test status

Full Java 21 Maven `verify` finished **BUILD SUCCESS** at 2026-10-25T19:10:25+05:30 in 11:01 min: **997 executed checks passed**, zero failures/errors. Totals are independently aggregated from one final full run; earlier attempts/focused reruns are not added.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / service / configuration / CLI | 581 | 0 |
| Real PostgreSQL | 398 | 0 |
| Real Kafka + PostgreSQL | 7 | 0 |
| Packaged CLI | 11 | 0 |
| Docker-dependent | 0 | 387 |

Adds 48 unit/configuration/protocol/HTTP, 34 PostgreSQL invocations and 1 packaged sandbox check over M17. New coverage includes exact-byte HMAC and an independent Python fixture, header duplication/timestamp/skew/body-expiry checks, strict JSON/UTF-8 and bounded decimal exponents, actual merchant/CSRF isolation, duplicate/conflicting/late results, unknown/foreign/READY refusal, live-worker races, rollback, contention, owned metadata, receiver/outbound disablement, review state and exact V17 upgrade before-images. The real servlet receiver resolves a deliberately lost provider reply; a packaged process is forcibly killed/restarted before redelivering the identical signed event body. Existing operator CLI tests deliberately inherit invalid enabled-provider/webhook settings and pass via explicit CLI disable flags.

The first focused database run exposed a test expectation: an injected repository exception is translated to the receiver's retryable 503, while an injected service/outbox exception returns 500. The assertion was corrected and all rollback/evidence assertions retained. A missing import in the new upgrade fixture was also corrected. The first full run encountered transient NOWAIT table-lock failures in older approval/purge fixtures while automatic analyze was active in the disposable database. That known failing run was stopped before packaged checks and retained. The final fresh database was migrated through the headless CLI, with automatic maintenance disabled on its 27 public test tables only; production/other database settings, application locking and assertions were unchanged. Evidence: `.tools/milestone18-verify.log`, focused logs, `.tools/milestone18-first-full-maintenance-attempt.log`, bootstrap/maintenance-isolation logs and `.tools/milestone18-qa.json`.

Actual PostgreSQL 18.6 / Kafka broker 3.9.1 on Windows. Docker was unavailable, so PostgreSQL 17.6/container checks skipped; Flyway warns about PostgreSQL 18 tested support. Linux, production ingress/load, multi-broker/mixed-version deployment, live vendors, key-ring rotation, power-loss durability, independent backup/restore and multi-instance CSRF remain unverified. Sandbox automatic delivery is bounded best effort, with explicit journal-backed redelivery rather than a durable retry worker.

Stopped only the verified M18 Kafka process and workspace PostgreSQL cluster, which completed its shutdown checkpoint. No listeners remain on 55432/19092/19093; unrelated PostgreSQL 5432 and the separate project were untouched. Final application sources/resources predate the tested JAR. No commit or push was requested or made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–18 implementation, including verified incoming provider callbacks.

**CURRENTLY POSSIBLE:** ✓ Merchant-owned sandbox authorization/capture/full refund with HTTP or callback confirmation, exact retries and durable receipts.

**NOT IMPLEMENTED YET:** ✗ Reliable outgoing merchant notifications, permanent-receipt repair, key-ring rotation, partials/fees/void, settlement, Redis/risk, full observability, restore/retention closure and deployment hardening. No live funds/vendor integration.

**NEXT LOGICAL MILESTONE:** 19 — reliable outgoing merchant webhooks. Approximately seven proposed blocks remain, 19–25.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Webhooks and asynchronous confirmation
- HMAC-SHA256 and shared-secret authentication
- Raw-body signatures and replay windows
- Dedicated security chains and CSRF boundaries
- Durable inbox deduplication
- Atomic receipt/payment/ledger/outbox transactions
- Out-of-order callbacks and completion races
- Deferred constraints and immutable audit evidence
- Bounded queues and explicit redelivery
- Fault injection and process-restart testing
