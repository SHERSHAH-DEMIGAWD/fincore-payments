# Milestone 16 — Merchant authentication and tenant isolation

Implemented 2026-10-25. [Learning guide](MILESTONE_16_LEARNING_GUIDE.md) · [Merchant runbook](MERCHANT_ACCESS_RUNBOOK.md) · [Remaining roadmap](ROADMAP.md).

## 1. What was implemented

- Database-backed merchant Basic authentication with BCrypt and separate operator authentication.
- Safe merchant identity and CSRF-token endpoints; credentials required on each request.
- Immutable payment ownership and service-level checks for every merchant payment/read/transition path.
- Merchant-scoped idempotency keys with original-response replay and atomic payment/outbox writes.
- Merchant-filtered ledger balances and protected journal/event/projection reads.
- Sealed legacy ownership preserving historical rows and retry snapshots during V16 upgrade.
- Closed fallback routes, generic credential errors and a bounded roadmap through the proposed Milestone 25 finish.

## 2. Concepts / technologies used

- Spring Security / ProviderManager / BCrypt — independent merchant credentials and authenticated principals.
- CSRF / security-context handling — token sessions without persisted merchant authentication.
- Tenant ownership / object-level authorization — owned reads, transitions and aggregates.
- Composite PostgreSQL keys / deferred foreign keys / transactions — scoped atomic retries.
- Flyway / immutable triggers — compatible legacy scope and ownership protections.
- JUnit / Mockito / real PostgreSQL / Kafka — isolation, migration, rollback and existing regressions.

## 3. Important files

- `MerchantSecurityConfiguration.java` / `MerchantPrincipal.java` / `MerchantContext.java` — authentication and trusted identity.
- `MerchantCredentialRepository.java` / `MerchantIdentityController.java` — credential lookup, identity and CSRF metadata.
- `MerchantPaymentService.java` — scoped HTTP authorization before existing financial services.
- `Payment.java` / `PaymentService.java` / `IdempotencyRepository.java` — merchant ownership and scoped claims.
- `LedgerRepository.java` / existing payment controllers — scoped totals and protected delegates.
- `V16__add_merchant_identity_and_payment_scope.sql` — credential/ownership/composite-key migration.
- Merchant security/service tests and earlier HTTP fixtures — real credentials and preserved regression coverage.
- Learning guide, runbook and roadmap — concepts, tradeoffs, commands and completion scope.

## 4. Request flow

```text
POST /api/v1/payments + Basic + CSRF + Idempotency-Key
→ MerchantCredentialRepository.load → MerchantPrincipal
→ PaymentController.createPayment
→ MerchantPaymentService.createPayment
→ PaymentService.createPayment(merchantId, key, request)
→ IdempotencyRepository.claim + PaymentRepository.saveAndFlush + OutboxService.record
→ PostgreSQL transaction → PaymentResponse
```

## 5. Important design decisions

- Merchant identity comes from the authenticated principal, never a client-supplied tenant ID.
- Separate authentication managers prevent operator/merchant credential crossover.
- Credentials are checked per request; the session stores CSRF state only.
- Composite keys and a deferred ownership FK preserve atomic retry semantics.
- Historical data stays in a disabled legacy scope; journal/event envelopes are not rewritten.
- Application isolation protects HTTP callers; trusted database/operations access remains privileged.

## 6. Test status

Final Java 21 Maven `verify`: **BUILD SUCCESS — 830 executed checks passed, zero failures/errors**.

| Group | Passed | Skipped |
| --- | ---: | ---: |
| Unit / MVC / service / configuration / CLI | 482 | 0 |
| Real PostgreSQL | 333 | 0 |
| Real Kafka + PostgreSQL | 7 | 0 |
| Packaged CLI | 8 | 0 |
| Docker-dependent | 0 | 336 |

Adds seven service-boundary checks and 25 real PostgreSQL/HTTP merchant-security invocations over Milestone 15. The successful focused run also passed 63 unit/MVC/service and 56 PostgreSQL checks; reruns are not added to totals. Fresh V1–V16, V15 legacy-ownership/claim replay and prior upgrade regressions passed. Evidence: ignored `.tools/milestone16-verify.log`, `.tools/milestone16-focused-final.log` and independently aggregated `.tools/milestone16-qa.json`.

Coverage includes actual BCrypt/Basic credentials, CSRF token/cookie flow, rotation/disablement, separate providers, spoofed tenant IDs, every foreign read/transition, owner lifecycle/views, independent balances, scoped/conflicting/concurrent retries, late rollback, ownership/claim/identity guards and exact preservation of old fields. Earlier financial and Kafka assertions remain; source-less snapshots retain internal readability and now require ownership for HTTP access.

Initial targeted testing found two fault-injection setup errors, corrected by stubbing the underlying transactional spy. The first full attempt found an old V12 upgrade assertion comparing all columns; it was strengthened to compare every original field and explicitly verify new legacy ownership. That known failing attempt was stopped before packaged checks and retained as `.tools/milestone16-first-full-attempt.log`. Final full verification used a new disposable database and completed without assertion failures.

Actual PostgreSQL 18.6 / Kafka broker 3.9.1. Docker was unavailable: PostgreSQL 17.6 container checks skipped; Flyway warns PostgreSQL 18 exceeds tested support. Linux, production load, multi-broker/mixed-version deployment, multi-instance CSRF sessions and production identity/transport hardening remain unverified. No live provider or settlement functionality was added.

The interrupted session resumed after the successful full run; no application source changed or tests needed rerunning. Stopped only the verified session Kafka process and workspace PostgreSQL cluster; PostgreSQL completed its shutdown checkpoint. No listeners remain on 55432/19092/19093. The tested JAR covers all final application source/resources; unrelated PostgreSQL 5432 and the separate project were untouched. No commit or push was made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–16, including merchant authentication and tenant isolation.

**CURRENTLY POSSIBLE:** ✓ Authenticate merchants and isolate their payment commands, retries and financial/event reads.

**NOT IMPLEMENTED YET:** ✗ Provider integration, incoming/outgoing webhooks, expanded accounting/settlement, Redis/risk, full observability, restore/retention closure and production hardening.

**NEXT LOGICAL MILESTONE:** 17 — sandbox payment-provider integration. Approximately nine proposed milestones remain through 25; see the roadmap's scope and finish criteria.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Authentication versus authorization
- Spring Security filter chains and authentication providers
- BCrypt and credential lifecycle
- Typed principals and SecurityContext
- CSRF with stateless authentication
- Multi-tenant ownership and object-level authorization
- Composite keys and deferred foreign keys
- Atomic idempotency and immutable snapshots
- Tenant-filtered ledger aggregation
- Legacy migration and real-credential testing
