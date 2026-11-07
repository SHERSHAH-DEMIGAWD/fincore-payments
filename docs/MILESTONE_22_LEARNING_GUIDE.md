# Milestone 22 — Redis admission controls and deterministic risk rules

**Latest continuation (2026-10-27):** [Milestone 23 — payment observability](MILESTONE_23_LEARNING_GUIDE.md) adds private observer credentials, bounded metrics/traces, sampled source backlogs and freshness-aware alerts. Read its [runbook](OBSERVABILITY_RUNBOOK.md) and [verification summary](MILESTONE_23_SUMMARY.md). M24 restore/operational closure is next; two planned blocks remain through M25. Older next-step statements below are historical, and the Word volumes retain their original scope.

Implemented 27 October 2026 in the independent `fincore-payments` backend. Continue from [Milestone 21](MILESTONE_21_LEARNING_GUIDE.md). Use the [runbook](PAYMENT_RISK_RUNBOOK.md), [verification summary](MILESTONE_22_SUMMARY.md) and [roadmap](ROADMAP.md).

## 1. Why this block exists

Correct journals and safe retries do not answer whether a new payment should enter the system. A merchant might submit unusually large amounts, use a currency outside the demo's supported risk policy, or flood creation with distinct request keys. Those are admission decisions, separate from committing a financial effect.

M22 adds three opt-in checks at the authenticated HTTP payment-creation boundary: one supported currency, a maximum original payment amount in that currency, and a merchant quota for newly admitted creation intents during a Redis window. These are deterministic screening rules. No learned fraud score, customer/card/device data, case review workflow or chargeback model is introduced.

The distinction matters: Redis controls short-lived admission capacity; PostgreSQL remains the authority for request identity, saved decisions, payments, journals and events. A Redis ALLOW does not capture money. M17/M18 still decide when a confirmed provider result may be applied, and M21 still compares independent provider evidence afterward.

## 2. Scope and defaults

| Setting / boundary | Implemented behavior |
| --- | --- |
| Enabled switch | Off by default; disabled mode creates no Redis connection factory |
| Entry point | New POST `/api/v1/payments` through `MerchantPaymentService.createPayment` |
| Currency | One configured uppercase currency, default INR; other currencies receive a policy denial |
| Amount | Positive exact NUMERIC(19,2), at most configured maximum; default INR 100,000.00 |
| Velocity | Default 20 distinct admitted intents per merchant/policy window of 60 seconds |
| Allowed ranges | 1–1,000 admissions; 1–3,600 seconds; client timeout 50–1,000 ms |
| Redis endpoint | Standalone loopback IPv4 only, database 0–15, optional ACL credentials |
| History | Merchant-owned UUID-keyset pages, limit 1–100 |

Authorize, capture, refund, provider retries, incoming callbacks, settlement evidence and existing payment reads are not charged to this quota. Recovery must remain possible after a payment has entered the system. This is also not an IP-based API rate limiter: authentication cost, invalid payloads, GET traffic and every other route still need their own capacity planning.

The amount rule uses original requested payment principal, not fees, net merchant payable, accumulated balances or FX conversion. Refusing unsupported currencies avoids treating equal numeric values in different currencies as equal exposure.

## 3. The request flow and transaction boundary

```text
POST /api/v1/payments + merchant Basic/CSRF + Idempotency-Key
→ PaymentController.createPayment
→ MerchantPaymentService.createPayment [NOT_SUPPORTED]
→ PaymentRiskService.requireAdmission
→ PaymentRiskTransactions.assess [REQUIRES_NEW, Read Committed]
   → key advisory fence + existing financial retry lookup
   → immutable risk intent + terminal decision lookup
   → amount/currency rules
   → RedisRiskVelocity.reserve → payment-admission.lua
   → append risk decision → commit evidence
→ accepted result: PaymentService.createPayment
   → PostgreSQL idempotency claim + payment + outbox → commit financial creation
→ PaymentResponse
```

`MerchantPaymentService` suspends an ambient transaction for this creation coordinator. In the normal HTTP flow, the risk check finishes before the financial creation transaction begins. The Redis call occurs inside a short **nonfinancial evidence transaction**, protected by a per-merchant/request advisory fence; it does not lock payment/journal rows. That transaction has a five-second JDBC transaction timeout, and Redis connection/command operations have configured client timeouts. These are not a guarantee of an absolute end-to-end HTTP deadline under pool starvation or operating-system delays.

An evidence result commits before `PaymentRiskService` throws a policy/throttle/outage HTTP exception. Throwing inside the assessment transaction would roll back the very refusal record we want to inspect. Financial creation uses the existing M2 transaction afterward, preserving its atomic claim/payment/outbox behavior and M19 notification enrollment.

This intentionally avoids an XA/distributed transaction between Redis and PostgreSQL. The cost is that the two stores can disagree temporarily, which the retry rules below handle explicitly.

## 4. Two kinds of durable identity

`payment_risk_intents` binds merchant + creation key to the canonical request fingerprint, exact principal and currency. The reference remains part of that fingerprint, without being copied into this extra table. A changed request under an already bound key returns the existing idempotency conflict before another reservation.

`payment_risk_decisions` appends observations linked to that intent. The API returns a hash of the creation key rather than its raw value, plus original amount/currency, outcome, reason, policy snapshot/hash, counter observation and timestamp. SHA-256 here identifies inputs; it does not authenticate evidence as an HMAC would.

| Outcome | Meaning | Same-request behavior while controls are enabled |
| --- | --- | --- |
| ALLOW | Static rules passed and Redis reserved capacity | Reuse this permanent admission without another Redis call |
| DENY | Unsupported currency or amount above maximum | Return the original denial; changing intent conflicts |
| THROTTLED | Current Redis window is full | Append observation, return 429/Retry-After; original intent may be reassessed later |
| UNAVAILABLE | Redis transport/script/response could not establish a valid result | Append observation, return 503; original intent may be reassessed later |

Only ALLOW and DENY are terminal, with at most one terminal decision per intent. Throttle/outage results are retained history, not permanent rejection. Invalid authentication, CSRF, input, busy fences and failed database transactions do not all produce a decision row.

Already committed M2 creation records are checked first. An exact original financial retry returns its saved payment snapshot even when today's risk policy would deny that amount/currency or Redis is offline. Existing creation idempotency remains the durable duplicate-payment protection; Redis markers are not a replacement.

## 5. What the Lua script does

Each merchant/policy has one Redis hash key. Its name includes a merchant hash tag and policy hash; it contains `used` plus one marker per admitted intent. The marker token binds both the creation key and canonical request fingerprint. Raw merchant references, request keys and amounts are not stored in the hash.

On the first admission, the script sets the count/marker and starts the TTL. Another call with the same token returns its original reservation count. A distinct intent increments only while capacity remains. Once full, a rejection does not increment the count or create another marker. Later traffic does not extend the window. Each hash therefore has at most `maxAdmissions + 1` fields through this application path.

For a deduplicated reservation, `usedCount` is that token's saved admission ordinal, not today's total used count; remaining TTL comes from the current hash observation. A throttled response reports the full current count. Decision history is evidence of an admission result, not a live Redis-capacity dashboard.

Redis executes the script without interleaving other commands, preventing two clients from both reading the last free slot and claiming it. Spring Data's script executor uses EVALSHA and falls back to EVAL when the script cache does not contain it. [Redis scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/), [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).

The script refuses inconsistent counts, missing/nonpositive TTLs, invalid marker values and wrong key types. The Java gateway also validates the returned flag/count/TTL tuple before it can become ALLOW. It never deletes corrupt state automatically. Script atomicity is not an instruction to assume arbitrary runtime failures roll back all earlier writes; uncertain results are treated conservatively as unavailable.

This is a window **anchored at first admission**, not a sliding “previous 60 seconds” query. Requests close to expiry can use one window's remaining capacity and then a new window quickly. Policy changes produce a new hash/window, so all application instances must use consistent settings and namespace.

## 6. Worked failure cases

### Lost payment response

The original creation committed. The client repeats the same key/body. The risk transaction finds M2's saved fingerprint and bypasses Redis; M2 returns the original snapshot. There is one payment and one original ALLOW decision.

### Redis reply lost after reservation

Redis may have reserved the token even though the application saw an exception. The API records UNAVAILABLE and creates no payment. With unchanged policy and an unexpired hash, the original retry finds its marker and does not consume another slot. PostgreSQL then records ALLOW and creation may proceed.

### PostgreSQL evidence commit fails after Redis succeeds

No durable risk intent/decision may remain. A retry derives the same key-and-body token, so a surviving Redis marker recovers that reservation. If the token has expired or Redis lost its data, capacity can be consumed again. No automatic compensation tries to “return” a slot, because a lost response makes ownership uncertain.

### Financial creation fails after ALLOW committed

The risk grant remains while payment/idempotency/outbox rows roll back together. The exact retry reuses that grant and retries M2 without another reservation. Capacity is conservative: a failed later creation can occupy a slot even if no payment ultimately commits.

### Window full

A new request receives THROTTLED, a decision ID and Retry-After. Its immutable intent remains bound. After capacity resets, the same key/body can receive a new ALLOW. Changing amount/reference/currency under that bound key conflicts.

### Redis stopped, restarted or corrupted

While Redis is unavailable, new otherwise eligible requests fail closed with 503. Static rule denials can still be recorded without contacting Redis. Previously accepted financial/admission retries continue.

When Redis restarts empty, an absent key begins a new window. This can admit more requests than a strict rolling historical quota would allow. The Compose service is intentionally ephemeral; counters are a soft operational control, not a hard financial exposure limit. Corrupt existing state is refused rather than erased. PostgreSQL loss is a separate database failure, not a reason to accept a Redis-only payment.

## 7. Policy lifetime and the off switch

A committed ALLOW is permanent for that exact intent in this learning implementation, even if actual payment creation is retried after the Redis window or a later policy change. A committed DENY remains terminal while controls are enabled. There is no grant expiry, revocation or manual risk-review API.

Explicitly disabling the feature bypasses admission checks, including historical risk denials; this is an administrator configuration choice, not automatic outage fallback. Existing risk history stays readable. A later re-enabled application checks already committed financial retries first. Mixed enabled/disabled instances can bypass the intended control, so enabling this feature requires coordinated configuration.

While disabled, a risk-only rejected key is not enforced as a creation binding; M2's already committed financial keys remain binding. Consequently, a disabled-mode creation can have different intent from earlier risk-only evidence for that key. The earlier intent/decision rows remain unchanged observations. This off switch is deliberately broad and must not be confused with a grant-revocation or universally enforced authorization system.

The feature protects the merchant HTTP creation path. Trusted internal `PaymentService` entry points retain their existing direct capability; database owners and trusted operators are not restricted by a new risk authorization system. No database trigger makes Redis a financial prerequisite.

## 8. Database defenses

V22 adds only the risk intent/decision tables. Owned foreign keys, valid outcome/reason pairs, count/TTL bounds, one terminal decision per intent, body-key hashes and ordinary UPDATE/DELETE refusal reinforce evidence consistency. An insert trigger checks that currency/amount reasons actually agree with the saved request and policy snapshot.

These checks protect against application mistakes; they cannot verify that a Redis reply actually occurred or that a privileged database owner chose an authentic policy. Financial rows and earlier migrations remain governed by their existing constraints. Risk history has bounded pages but no total retention limit or purge executor; operational retention remains part of M24.

## 9. Alternatives and why this implementation uses these choices

| Chosen approach | Alternative | Reason and tradeoff |
| --- | --- | --- |
| Shared Redis capacity | Per-JVM counters | Multiple API instances share observations; introduces availability and state-loss behavior |
| One Lua operation | Separate read/increment/expiry calls | Avoids interleaving and client-side expiry gaps; requires script permissions and validation |
| First-admission fixed window | Sliding log or token bucket | Small bounded state and an explainable quota; permits boundary bursts |
| Count admitted intents | Count every HTTP attempt | Safe retries are not charged repeatedly; does not protect all ingress traffic |
| One policy currency | Compare all currencies numerically | Makes the amount ceiling meaningful without FX; narrows supported creation while enabled |
| Fail closed for new work | Automatic fail open during Redis failure | Does not silently bypass a configured control; reduces availability |
| PostgreSQL durable decisions | Redis-only decisions | Historical retries survive Redis loss; requires a separate evidence transaction |
| Conservative reservation retention | Compensating decrement after failure | Avoids releasing capacity whose remote outcome is uncertain; can waste capacity |
| Existing financial retries first | Re-run today's checks on every retry | Preserves accepted request recovery; historical grants can outlive policy changes |
| Deterministic rules | ML fraud scoring | Inputs/outcomes are explainable and testable here; provides no learned fraud detection |

Other choices may be better for a different product. Hard financial velocity limits need stronger durable accounting than an evictable/resettable Redis counter. A production network boundary, grant lifecycle and richer signals require their own contracts and verification.

## 10. Code and verification map

- `RiskConfiguration` / `RiskProperties`: opt-in standalone Lettuce connection, local endpoint, bounded settings and redacted credentials. Spring Boot manages the Redis starter/Lettuce dependency versions; default Redis auto-configuration is excluded so disabled/headless use creates no Redis client. [Spring Boot 3.5 Redis support](https://docs.spring.io/spring-boot/3.5/reference/data/nosql.html#data.nosql.redis).
- `RedisRiskVelocity` / `redis/payment-admission.lua`: scoped atomic reservation and response validation.
- `PaymentRiskService` / `PaymentRiskTransactions`: validation, transaction ownership, retry semantics and HTTP refusal after audit commit.
- `PaymentRiskRepository` / V22: immutable intent/decision persistence and scoped reads.
- `PaymentRiskController`: GET `/api/v1/merchant/risk-decisions` and `/{decisionId}`.
- `MerchantPaymentService`: creation coordinator; other financial operations retain their paths.

LuaJ tests execute the actual script with a small fake Redis command surface. They validate control flow, bounds, duplicate tokens and TTL logic, not a real Redis server or its concurrency/durability. [LuaJ project](https://github.com/luaj/luaj). PostgreSQL tests mock only the Redis gateway while exercising real transactions, authentication, rollback and schema guards. Separate local-Redis/Testcontainers suites exercise the actual server, and a two-container test covers the creation boundary without mocked admission. The [summary](MILESTONE_22_SUMMARY.md) distinguishes executed tests from skips and records environment limits.

## 11. Holistic impact and next work

The platform now separates admission (M22), instruction and confirmation (M17/M18), local financial commitment (M1–3/M20), delivery (M4–5/M19) and independent comparison (M21). Failure at one stage cannot be explained merely by another stage returning success. Stable identity connects retries without confusing permission to enter with proof that money moved.

Next is M23 observability: bounded-label metrics, useful traces and actionable alerts across payment creation, Redis admission, provider uncertainty, delivery and recovery. M24 covers restore/operational closure; M25 covers deployment and final end-to-end validation. Three planned blocks remain after M22, with verification limits tracked explicitly rather than treating milestone count as production readiness.

### TOPICS TO LEARN FROM THIS SESSION

- Admission control versus financial authorization
- Redis hashes, TTLs and fixed windows
- Lua atomic execution and script caching
- Idempotent reservations and cross-store uncertainty
- Fail-closed outage behavior
- Durable risk decisions and policy snapshots
- Transaction propagation and commit-before-refusal
- Advisory locks and tenant isolation
- Conservative capacity accounting
- Test doubles versus real infrastructure tests
