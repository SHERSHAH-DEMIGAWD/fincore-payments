# Milestone 22 — Redis admission controls and risk rules

Implemented 2026-10-27. [Learning guide](MILESTONE_22_LEARNING_GUIDE.md) · [Runbook](PAYMENT_RISK_RUNBOOK.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Opt-in merchant payment-creation amount, currency and admission-velocity checks.
- Atomic Redis Lua reservations with TTL, bounded markers and exact-intent deduplication.
- Durable immutable request binding, policy snapshots and risk decisions in PostgreSQL.
- Fail-closed Redis outage results and retryable throttling with decision/retry headers.
- Accepted creation/admission retries that remain available without Redis.
- Merchant-owned decision reads and bounded keyset history.
- Optional local Redis Compose profile and executable recovery/testing instructions.

## 2. Concepts / technologies used

- Spring Data Redis / Lettuce / Lua — shared atomic admission capacity.
- Redis hashes / TTL / fixed windows — bounded temporary reservations.
- PostgreSQL / Flyway / immutable evidence — durable request and policy history.
- UUIDs / SHA-256 fingerprints / idempotency — retries across store uncertainty.
- Spring transaction propagation / advisory locks — separate evidence and financial transactions.
- MockMvc / Mockito / LuaJ / Testcontainers — layered behavior and infrastructure checks.

## 3. Important files

- `MerchantPaymentService.java` — creation admission coordinator.
- `PaymentRiskService.java` / `PaymentRiskTransactions.java` — validation, decisions and commit-before-refusal.
- `PaymentRiskRepository.java` / `PaymentRisk.java` — owned persistence and response contracts.
- `RedisRiskVelocity.java` / `redis/payment-admission.lua` — atomic shared reservations.
- `RiskConfiguration.java` / `RiskProperties.java` — opt-in client and bounded policy.
- `PaymentRiskController.java` / `GlobalExceptionHandler.java` — history and refusal headers.
- `V22__add_payment_risk_evidence.sql` — append-only schema and guards.
- `docker-compose.yml` / risk tests / guide / runbook — local setup, verification and learning.

## 4. Request flow

```text
POST /api/v1/payments
→ PaymentController.createPayment → MerchantPaymentService.createPayment
→ PaymentRiskService.requireAdmission → PaymentRiskTransactions.assess
→ RedisRiskVelocity.reserve → payment-admission.lua
→ PaymentRiskRepository.insertDecision → PostgreSQL evidence commit
→ PaymentService.createPayment → PostgreSQL payment/idempotency/outbox commit
→ PaymentResponse
```

## 5. Important design decisions

- Only new HTTP payment creation consumes quota; recovery and existing financial operations remain available.
- Redis is temporary admission state; PostgreSQL retains financial authority and decisions.
- Amount rules use one configured currency, without implicit FX comparisons.
- Terminal ALLOW/DENY replay; THROTTLED/UNAVAILABLE can be reassessed with the same intent.
- Evidence commits before a refusal is thrown; financial creation is a separate transaction.
- Reservations are retained conservatively after later failure; no uncertain compensation decrement.
- Explicit disabling bypasses admission; automatic Redis outage handling fails closed.

## 6. Test status

Java21 Maven `clean verify` finished **BUILD SUCCESS** at **2026-10-27T17:42:36+05:30**, in 15:40 min: **1,179 executed tests passed**, zero failures/errors. These are one broad run's results; focused reruns and the separate transport probe are not added.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / service / configuration / CLI | 711 | 0 |
| Real PostgreSQL | 456 | 0 |
| Packaged CLI / sandbox | 12 | 0 |
| Docker-dependent | 0 | 446 |

M22 adds 25 unit/configuration/Lua/gateway cases and 16 native PostgreSQL cases. Coverage includes exact token retry, bounded marker count/expiry, malformed replies, invalid policy, disabled client, static denials, outage/throttle recovery, historical financial retry, risk/financial rollback separation, stable tokens after risk rollback, actual Basic/CSRF/ownership, contention, immutable/schema guards, history limits and V21→V22 payment preservation. Fresh V1–22 migration passed; V1–21 checksums match the previous milestone database. Packaged headless CLI passes with deliberately invalid inherited enabled risk settings.

A separate real-Lettuce probe against a TCP endpoint that accepts but never responds refused admission and returned in **2,330 ms**, within its five-second check budget. This is an observed cold-path test, not a 100ms whole-request latency guarantee or a real Redis pass. Its temporary listener/client were closed.

**Not executed:** real Redis Lua/concurrency/expiry checks, combined Redis/PostgreSQL creation tests and the interactive Docker runbook: this Windows machine has neither Docker nor WSL. The new server/container suites are supplied; their skips are not passes. LuaJ runs the actual script with fake Redis commands, and native PostgreSQL risk tests mock the Redis gateway. Native Kafka delivery, Linux, load, multi-instance/mixed-policy rollout, Redis persistence/HA, live funds and independent restore remain unverified. Actual Java21/PostgreSQL18.6; Flyway warns PG18 exceeds its tested support range.

Corrected an initial checked-I/O script-loading compile issue, an ambiguous test import, an outbox ownership count query and a fault stub placed on a mandatory transaction proxy. No financial assertion was weakened. Automatic maintenance was disabled only for public tables in the named disposable M22 database to avoid known NOWAIT/auto-analyze fixture contention.

Documentation links/anchors/fences and ten PowerShell blocks pass static checks. Final JAR includes the exact Lua source and V22 migration, covers final application source/resources and excludes the test-only LuaJ interpreter. Evidence is ignored workspace `.tools/milestone22-verify.log`, focused/probe logs, checksum/cleanup files and independently aggregated `milestone22-qa.json`. The owned PostgreSQL55432 cluster stopped with a completed shutdown checkpoint; data/logs were retained. No Redis/Kafka server was started, unrelated5432/project changed, or commit/push made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–22 implementation and available local/regression verification, with real Redis execution explicitly outstanding.

**CURRENTLY POSSIBLE:** ✓ Screen new creations, reserve shared capacity, inspect durable decisions and recover accepted/retryable intents without making Redis the financial database.

**NOT IMPLEMENTED YET:** ✗ Learned fraud scoring, all-route/IP limiting, hard durable exposure limits, grant expiry/revocation, Redis HA/remote/TLS, full observability, restore closure and final deployment validation.

**NEXT LOGICAL MILESTONE:** 23 — observability. Three proposed blocks remain, 23–25.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Admission control and deterministic risk rules
- Redis Lua, hashes and TTL windows
- Idempotency across Redis and PostgreSQL
- Fail-closed behavior and uncertainty
- Transaction propagation and durable refusal evidence
- Policy snapshots and historical retries
- Test doubles versus real infrastructure tests
