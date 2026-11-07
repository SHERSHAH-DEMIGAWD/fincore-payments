# Milestone 16 learning guide — Merchant authentication and tenant isolation

**Continuation:** [Milestone 17](MILESTONE_17_LEARNING_GUIDE.md) now adds sandbox provider commands under the same merchant boundary. A provider-managed payment refuses manual transitions. This chapter retains its historical Milestone 16 scope; [incoming provider webhooks](ROADMAP.md) are next after 17.

Implemented 2026-10-25 in the independent Java `fincore-payments` project. This continues [Milestone 15](MILESTONE_15_LEARNING_GUIDE.md). Use the [merchant runbook](MERCHANT_ACCESS_RUNBOOK.md) for setup, the [session summary](MILESTONE_16_SUMMARY.md) for verification, and the [bounded roadmap](ROADMAP.md) for the remaining project scope.

## 1. Why this milestone comes next

The platform already has payment transitions, retry-safe creation, balanced journals, outbox/Kafka delivery and extensive projection recovery/maintenance. Its HTTP payment endpoints were still public, idempotency keys shared one namespace, and currency balances aggregated every payment. Those are acceptable teaching foundations, but they cannot serve multiple independent merchants safely.

The updated roadmap returns to the original payment-platform capabilities. Milestone 16 establishes who the merchant is and which financial data that identity may access. The preservation/restore branch remains recorded as later operational work; no physical deletion is introduced.

**Example:** two shops both use `Idempotency-Key: order-123`. Previously they would collide in one global namespace. Now each shop can create its own payment. Repeating the same shop's original request returns its original response; changing its intent conflicts. Knowing the other shop's payment UUID provides no read or transition authority.

## 2. What is now protected

All existing `/api/v1/payments` routes, their ledger/event/projection subresources and `/api/v1/ledger/accounts/{currency}/balances` require merchant authentication. New `/api/v1/merchant/me` and `/csrf` endpoints expose safe identity and CSRF metadata.

| Surface | Authentication and scope |
| --- | --- |
| Payment create | Database-backed merchant credentials; ownership comes from the authenticated principal. |
| Payment read / authorize / capture / fail / refund | Same identity plus an authoritative owned-payment check. |
| Payment journals / outbox events / routed projection | Ownership checked before invoking the existing read service. |
| Currency balances | Totals from journals belonging to this merchant's payments only. |
| Projection operator API | Existing separate operator provider, role and CSRF boundary; remains privileged and cross-merchant. |
| Headless operations CLI / database administrator | Trusted operational access, outside merchant HTTP isolation. |

Unknown/unmatched routes are denied by the fallback chain. There are no default merchant credentials, public signup, password-reset endpoints or new merchant-administration HTTP API.

## 3. Authentication and authorization solve different problems

Authentication establishes an identity from credentials. Authorization decides which records/actions that identity may access. A successful password check does not authorize every payment UUID.

`MerchantCredentialRepository.load` reads a merchant's UUID, exact username, BCrypt verifier and enabled status. `DaoAuthenticationProvider` checks those credentials and issues `MerchantPrincipal`, which carries the database UUID and `ROLE_MERCHANT`. A role label on an ordinary principal is insufficient for the merchant chain. `MerchantContext` repeats this typed-principal/authority requirement at the service boundary.

`MerchantPaymentService` obtains its identity from Spring Security's request context. It never accepts a merchant UUID from a body, query parameter or `X-Merchant-Id` header. A merchant reference such as `ORDER-123` is a business reference, not an account identity.

**Alternatives:** trusting a client-supplied merchant ID would let a caller select another tenant. Inferring ownership from merchant references would mix non-unique order labels with identities. Authentication without record ownership checks would still expose cross-merchant data. A server-issued principal plus durable ownership directly expresses the intended boundary.

## 4. Why Basic authentication and BCrypt were chosen

The project already includes Spring Security and BCrypt for its operator boundary. This milestone reuses the libraries with a separate database-backed merchant provider. Basic authentication avoids introducing token issuance, signing keys, refresh flows or a pretend identity provider before the underlying ownership rules work.

BCrypt stores a password verifier rather than plaintext. Merchant usernames are case-sensitive ASCII identifiers of 1–100 characters; accepted characters are letters, digits, dot, underscore and hyphen, starting with a letter/digit. Password verifiers must use the permitted BCrypt representation/cost; the database rejects plaintext. The runbook recommends a trusted generator and an appropriate cost, with test cost 4 used only for disposable fixtures.

**Costs:** Basic carries the original credential on each request and needs HTTPS outside loopback. Each request incurs a database lookup and BCrypt work. No rate limiting, MFA, password-recovery service, OIDC/OAuth integration or credential-management audit workflow is added. Do not interpret this as completed production identity management.

**Alternatives:** JWTs can decouple services from a password store, but introduce token trust/lifetime/revocation and key rotation. A conventional login session reduces repeated password hashing but needs session invalidation when credentials are disabled/rotated. An external identity provider is a stronger production direction when its lifecycle and operations are available. None replaces payment ownership checks; this step keeps that core problem visible.

## 5. Separate authentication providers and filter chains

The servlet application now uses three ordered chains:

```text
1. projection-routing operator paths → configured operator provider + PROJECTION_OPERATOR
2. payment / ledger / merchant paths → local merchant ProviderManager + MerchantPrincipal
3. remaining paths → deny
```

`MerchantSecurityConfiguration` constructs a local `ProviderManager` with its own `DaoAuthenticationProvider`. It has no parent fallback to operator credentials. The existing operator user store remains independent.

**Example:** a database merchant and configured operator may have the same username with different passwords. The merchant password cannot authenticate to the operator API, and the operator password cannot authenticate as that merchant. An operator role alone also cannot enter the merchant service.

**Alternative:** one combined username/password store would blur operator and merchant identities and risk unintended provider fallback or role crossover. Separate realms make ownership and privilege easier to audit, at the cost of two credential lifecycles. This is not an organization-wide IAM system.

The architecture follows Spring's support for [local authentication managers and providers](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html). Current application behavior is verified by real-credential integration tests, including the deliberately identical username case.

## 6. Fresh authentication and CSRF can coexist

The merchant chain does not persist or recover authentication from an HTTP session. `SessionCreationPolicy.STATELESS` and `NullSecurityContextRepository` require credentials on each request. Disabling an account or rotating its verifier is therefore observed on its next authenticated request, including requests carrying an old cookie. An already in-flight operation can still complete; there is no cancellation/fencing protocol for administrative credential changes.

CSRF protection remains enabled. A browser can send cached Basic credentials automatically, so removing authenticated-session persistence does not remove that risk. Spring documents [Basic authentication's CSRF exposure](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html).

`GET /api/v1/merchant/csrf` returns token/header metadata and establishes a CSRF session cookie. Writes require that token/cookie pair **and** merchant credentials. The cookie stores CSRF state, not a reusable merchant login. GET `/me` with only that cookie is unauthenticated.

**Alternatives:** disabling CSRF just because a chain is called stateless would ignore browser credential behavior. Retaining authentication in the CSRF session would make disablement dependent on session invalidation. The selected arrangement preserves the existing token/session workflow while checking credentials afresh. Session expiration/restart requires a fresh CSRF token; multi-instance CSRF-session operation is not validated here.

## 7. Durable payment ownership

V16 adds `payments.merchant_id`, a foreign key to `merchants`. `Payment.create(UUID, ...)` records the authenticated identity; JPA marks the field non-updatable, and a PostgreSQL trigger rejects ownership reassignment through SQL as well.

`MerchantPaymentService` checks `PaymentRepository.existsByIdAndMerchantId(paymentId, merchantId)` before payment reads, transitions, journal/event reads and routed projection reads. Foreign and missing UUIDs both return the existing `404 PAYMENT_NOT_FOUND` contract. The existing financial service then retains its transaction, optimistic-locking, journal and outbox behavior.

**Why immutable ownership:** a separate check followed by use would be unsafe if ownership could change between them. Here ownership cannot be reassigned through the normal application/database protocol. New merchant create joins the same transaction as claim, payment and outbox writes. A late write failure rolls back all of them.

**Alternatives:** controller-only checks are easy to omit on another endpoint. A global servlet filter can authenticate a caller but does not know each query's ownership semantics. Rewriting all financial logic into merchant controllers would duplicate tested transaction paths. The merchant service boundary centralizes authorization and delegates existing correctness-sensitive work.

The older global `PaymentService` methods intentionally remain trusted internal operations for existing tools/recovery/tests. New HTTP controllers inject `MerchantPaymentService`. This is application-enforced isolation over a shared database, not PostgreSQL row-level security or isolation from privileged database users.

## 8. Merchant-scoped idempotency without changing retry semantics

The key changes from one global primary key to a composite primary key:

```text
idempotency_records PRIMARY KEY (merchant_id, idempotency_key)
```

`IdempotencyRepository.claim(UUID, ...)` uses `INSERT ... ON CONFLICT (merchant_id,idempotency_key) DO NOTHING`. The affected row count still determines claim versus replay. The separate SELECT at Read Committed still sees the winning committed result after a concurrent conflict.

The logical request fingerprint remains unchanged: merchant reference, normalized money and currency. Merchant identity is represented by the database key, rather than mixed into a string label. A deferred composite foreign key from `(merchant_id,payment_id)` to `payments(merchant_id,id)` prevents a claim from naming another merchant's payment while retaining claim-before-payment insertion in one transaction. Accepted claim snapshots now reject UPDATE/DELETE.

**Example:** A/key and B/key both create once. A/key with a different amount returns 409. A/key after capture/refund returns A's original CREATED snapshot, as before. Concurrent identical requests for A produce one 201 and one 200; two merchants racing on the same client key can both get 201.

**Alternatives:** a UUID prefix or hash concatenated into the existing key would encode tenancy indirectly and complicate length/legacy collision rules. A second idempotency table would duplicate the retry protocol. Putting claims in Redis would add a cross-resource consistency problem before a Redis-specific need exists. Composite PostgreSQL identity keeps the existing atomicity model explicit.

## 9. Ledger isolation without rewriting historical journals

Account definitions remain the existing pooled `CLEARING` and `MERCHANT_PAYABLE` definitions per currency. There is no new wallet, account hierarchy or merchant balance entity.

`LedgerRepository.merchantBalances` joins entries → transactions → payments and includes only the authenticated payment owner. It returns posted account totals for that merchant. A merchant with no journals gets an empty list even when another merchant has accounts/entries; this avoids exposing other merchants' activity through empty-account differences. Capture/refund still create balanced immutable two-entry journals.

**Example:** A has captured 10 INR and B has captured 999 INR. A sees 10 in each relevant normal balance, not 1009. Refunding A makes A's normal balances zero and leaves B at 999.

**Alternatives:** authenticating the existing global aggregate would still leak other tenants' sums. Filtering only the response cannot unmix an already global sum. Introducing tenant-specific physical accounts now would require a larger accounting migration and business model. Deriving owned totals preserves history while providing scoped reads; it does not yet implement merchant settlement accounts, fees or payable disbursements.

## 10. Upgrade existing records honestly

Existing records do not establish a trusted merchant account association. V16 assigns old payments and claims to the reserved all-zero UUID `legacy-internal`. That identity is disabled, has no verifier, cannot be enabled by ordinary SQL and is explicitly excluded from credential loading. Original payment/claim fields and response snapshots remain unchanged.

Trusted headless/legacy creation methods keep this scope as their default. New HTTP credentials always come from a provisioned non-legacy merchant. There is no public adoption/reassignment endpoint or automatic reassignment to the first account created.

**Alternatives:** making all historical rows belong to the first user would expose data without ownership evidence. Deleting them would lose financial/retry history. Making the new field nullable would introduce an ambiguous anonymous access path into every query. A sealed legacy identity preserves data and makes the access limit explicit.

This migration also breaks anonymous API access intentionally. Clients must provision credentials and send Basic plus CSRF for writes. Old binaries are not supported for concurrent operation after the composite-key migration; their SQL targets a different uniqueness contract. Follow a coordinated application upgrade.

## 11. Events and operational scope stay explicit

The retained v1 payment event and response schemas do not gain merchant identity fields in this milestone. Existing immutable outbox/receipt envelopes are not rewritten. Merchant HTTP projection/event reads use authoritative co-located payment ownership, rather than trusting a consumer snapshot to grant access.

A source-less consumer event may still produce an operational projection, as the existing consumer contract allows. It does not invent a merchant owner. Such a projection is readable by trusted internal services but is refused by the merchant HTTP API. An owned payment awaiting projection has the separate `PAYMENT_PROJECTION_NOT_FOUND` result.

**Tradeoff:** avoiding a wire-contract rewrite preserves retained event/replay semantics now, but a separately deployed tenant-aware consumer would need a deliberate versioned ownership contract. Kafka/database access remains trusted infrastructure; securing that boundary and multi-broker operation are separate work. The operator API remains cross-merchant privileged access, not a merchant analytics API.

## 12. Actual request flow

```text
POST /api/v1/payments + Basic credentials + CSRF token/session + Idempotency-Key
→ MerchantSecurityConfiguration / DaoAuthenticationProvider
→ MerchantCredentialRepository.load → MerchantPrincipal
→ PaymentController.createPayment
→ MerchantPaymentService.createPayment → MerchantContext.merchantId
→ PaymentService.createPayment(merchantId, key, request)
→ IdempotencyRepository.claim / PaymentRepository.saveAndFlush / OutboxService.record
→ one PostgreSQL transaction
→ CreatePaymentResult → PaymentResponse (201, or 200 for exact replay)
```

```text
GET /api/v1/payments/{paymentId}/events
→ authenticated merchant principal
→ PaymentEventController.getPaymentEvents
→ MerchantPaymentService.getPaymentEvents / owned-payment check
→ OutboxService.getPaymentEvents
→ scoped authorized response, or 404 before reading another merchant's events
```

## 13. Verification and failure cases

New service tests verify that missing authentication or a role-only principal cannot enter the boundary, foreign ownership stops every delegated read/command, creation uses the authenticated UUID and balances never call the global aggregate.

Real PostgreSQL/MockMvc tests use actual Basic passwords and BCrypt verifiers, not mocked authentication. They cover missing/wrong/disabled/legacy credentials; safe identity output; real CSRF token/cookie handling; password rotation with an old cookie; operator-provider separation; scoped/replayed/conflicting/concurrent keys; spoofed IDs; all foreign reads/transitions; owner lifecycle/ledger/events/projection; independent balances; late rollback; DB ownership/FK/immutability guards; and V15→V16 migration with exact legacy replay.

Earlier HTTP regression fixtures now install an explicit trusted test principal and CSRF token. This preserves their financial, concurrency and broker assertions while exercising the new filter chain. Those fixtures are test-only; their legacy identity cannot authenticate in the application. Source-less consumer tests retain internal projection checks and now assert HTTP denial. The new credential suite uses ordinary request builders and proves the actual external boundary.

Final Java 21 Maven verification passed **830 executed checks**, zero failures/errors: 482 unit/MVC/service/configuration/CLI, 333 real PostgreSQL, 7 native Kafka and 8 packaged CLI. Docker-dependent checks skipped: 336. Actual PostgreSQL 18.6 / Kafka broker 3.9.1 were tested; container PostgreSQL 17.6, Linux, production load, multi-broker and multi-instance CSRF behavior remain unverified. See the [summary](MILESTONE_16_SUMMARY.md) for evidence and earlier corrected attempts. Reruns are not added and no Docker skip is called a pass.

## 14. How this changes the project's next steps

This milestone turns public financial learning endpoints into merchant-owned API operations. It makes future provider requests, callbacks, merchant webhooks, risk rules and reconciliation attach to a durable identity. Without that identity, a provider integration would still have ambiguous ownership and shared retry keys.

The [roadmap](ROADMAP.md) fixes the remaining learning-platform work around payment capabilities, with provider integration next. Preservation verification, restoration and cleanup remain explicit operational obligations; they do not indefinitely replace the business milestones. Completion means a documented, tested end-to-end sandbox platform, not a promise of operating a live regulated payment business.

## TOPICS TO LEARN FROM THIS SESSION

- Authentication versus authorization
- Multi-tenant ownership and shared schemas
- Spring Security filter chains
- ProviderManager and DaoAuthenticationProvider
- BCrypt password verification
- Typed authenticated principals and SecurityContext
- Stateless authentication versus CSRF sessions
- Object-level authorization and 404 behavior
- Composite primary and foreign keys
- Deferred constraints and atomic idempotency
- Immutable ownership and audit snapshots
- Tenant-filtered ledger aggregates
- Legacy-data migration and compatibility
- Real-credential integration and concurrency testing
