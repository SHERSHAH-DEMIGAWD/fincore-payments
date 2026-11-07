# Milestone 17 learning guide — Sandbox payment-provider integration

**Continuation:** [Milestone 18](MILESTONE_18_LEARNING_GUIDE.md) now verifies incoming callbacks and confirms existing commands with durable receipts. Follow the [webhook runbook](PROVIDER_WEBHOOK_RUNBOOK.md). This chapter retains its M17 scope; callbacks default off, and reliable outgoing merchant notifications are next after 18.

Implemented 2026-10-25 in the independent Java `fincore-payments` project. Continue from [Milestone 16](MILESTONE_16_LEARNING_GUIDE.md); use the [provider runbook](PROVIDER_INTEGRATION_RUNBOOK.md) to run the demo and the [summary](MILESTONE_17_SUMMARY.md) for final test evidence. The [roadmap](ROADMAP.md) defines the remaining scope. The Word guide remains the Milestones 1–10 snapshot.

## 1. Why this milestone matters

Earlier milestones made local payment transitions, journals and events reliable. Milestone 16 established the merchant that owns each payment. Neither meant that an external payment system had authorized or captured anything: the manual transition endpoints only changed our database.

Milestone 17 adds a separate sandbox provider process reached through real HTTP. The application now commits an intention, asks that process for a decision, and applies a confirmed result to the existing payment/ledger/outbox transaction. This introduces the central distributed-payment problem: the other system may succeed even when our application receives no usable response.

**Example:** the sandbox accepts a capture and commits its decision. Its reply is deliberately cut short. Our database must keep the payment AUTHORIZED and the command UNKNOWN. Calling that a decline would be false; creating another capture command could duplicate the external operation. Retrying the original command recovers the saved provider result and posts one local capture journal.

This is an educational local sandbox, with no card data, vendor account or real funds. The adapter boundary is useful preparation for a vendor integration, but a real vendor's authentication, idempotency, status and webhook contracts still require their own implementation.

## 2. What was added

- Merchant-owned AUTHORIZE, CAPTURE and full REFUND commands through a new provider-operation API.
- A Java HTTP adapter and independently runnable, authenticated loopback sandbox with a restartable decision journal.
- Durable operation identity, frozen intent, provider binding, attempts and progress in PostgreSQL.
- Exact retries after lost replies, lease expiry or a local completion rollback, using the original provider idempotency key.
- Safe owned progress/detail reads and refusal of manual transitions on provider-managed payments.
- Confirmed outcomes reuse the established payment, balanced-ledger and transactional-outbox paths.

Provider use is disabled by default. Existing unbound manual teaching flows continue to work. Enabling integration does not automatically enroll old payments or dispatch unfinished commands in the background.

## 3. The important identities

| Identity | Owner and meaning |
| --- | --- |
| Merchant UUID | Comes from the authenticated `MerchantPrincipal`; never selected by request payload. |
| Payment UUID | Existing merchant-owned payment and frozen amount/currency. |
| Request UUID | Merchant's logical command identifier; unique across that merchant's payments. |
| Operation UUID | Server-generated durable command identifier; sent unchanged as provider `Idempotency-Key` on every attempt. |
| Provider instance UUID | Durable sandbox namespace, saved in the binding, command and sandbox journal. |
| Lease token | Fresh internal UUID per attempt, used to reject completion by an older worker; omitted from HTTP views. |
| Provider payment/operation IDs | Validated remote references returned by this sandbox protocol. |

These IDs solve different problems. A new lease token is appropriate for a new worker attempt. A new provider operation UUID is unsafe for retrying the same uncertain effect. A merchant request UUID alone is also unsuitable as a global provider key because two merchants may use the same value.

The existing payment-creation `Idempotency-Key` and the new provider command `requestId` are separate namespaces. Creating a payment and authorizing it are different logical operations.

## 4. Actual request flow and transaction boundaries

```text
POST /api/v1/payments/{paymentId}/provider-operations
  → merchant Basic authentication + POST CSRF
  → ProviderOperationController.submit
  → ProviderOperationService.submit                 [NOT_SUPPORTED]
      → ProviderOperationTransactions.prepare        [REQUIRES_NEW, READ_COMMITTED]
          → owned source lock, exact intent/binding → commit READY
      → ProviderOperationTransactions.claim          [REQUIRES_NEW, READ_COMMITTED]
          → attempt + lease token/deadline           → commit IN_FLIGHT
      → HttpSandboxPaymentProvider.execute           [no database transaction]
          → POST sandbox /v1/operations
          → SandboxProviderStore.execute             [persist/replay remote decision]
      → ProviderOperationTransactions.finish         [REQUIRES_NEW, READ_COMMITTED]
          → validate identity + fence token + source version/state
          → PaymentService.applyProviderResult
          → payment + capture/refund ledger + OutboxService.record
          → commit operation and local effects together
  → ProviderOperation.Result
```

If the provider response cannot be trusted or obtained, `ProviderOperationTransactions.uncertain` records UNKNOWN in its own transaction. It does not fail the payment or invent a journal.

`NOT_SUPPORTED` suspends an inherited caller transaction. The separate proxied transaction bean makes `REQUIRES_NEW` effective; calling annotated private/self methods would not provide the same Spring proxy boundary. An integration test invokes the route inside an older Repeatable Read transaction, then rolls the caller back: the confirmed provider completion remains committed, and HTTP still runs outside a transaction.

Suspending a caller transaction does not commit it or release its existing connection/locks. Callers should avoid holding financial locks around this orchestration. The guarantee is that provider I/O joins no active Spring transaction and that each provider database stage owns its separate commit, not that arbitrary callers cannot retain unrelated resources.

**Why avoid one transaction around HTTP?** It would retain locks and a connection while waiting on another process, yet rolling PostgreSQL back could never undo a provider decision. Short independent database transactions make that gap explicit and recoverable. The cost is additional states and recovery logic.

## 5. Command state versus payment state

| Operation state | Meaning and permitted next step |
| --- | --- |
| READY | Intent is committed; a caller may claim an attempt. |
| IN_FLIGHT | One attempt owns a lease; another caller sees progress until expiry. |
| UNKNOWN | No confirmed usable outcome; retry the exact original command. |
| APPLIED | Confirmed approval and corresponding local financial/state effect committed. |
| DECLINED | Confirmed provider decline committed; local effect depends on action. |
| REQUIRES_REVIEW | Provider outcome is known, but local source no longer matches frozen intent; no automatic application or new command. |

```text
READY → IN_FLIGHT → APPLIED / DECLINED / REQUIRES_REVIEW
             └──→ UNKNOWN → IN_FLIGHT
      expired IN_FLIGHT → IN_FLIGHT with a fresh token
```

Progress HTTP 200 means the command was processed or found; inspect `operation.state`, `providerOutcome` and `localEffectApplied`. HTTP 200 does not certify approval. Historical exact retries return the stored command even after later payment transitions, provider disablement or instance configuration change.

| Confirmed result | Local payment/ledger effect |
| --- | --- |
| AUTHORIZE approved | CREATED → AUTHORIZED; event, no financial journal. |
| AUTHORIZE declined | CREATED → FAILED; event, no financial journal. |
| CAPTURE approved | AUTHORIZED → CAPTURED; one balanced capture journal and event. |
| CAPTURE declined | Remains AUTHORIZED; no journal/event for a nonexistent state transition. |
| REFUND approved | CAPTURED → REFUNDED; one reversal journal and event. |
| REFUND declined | Remains CAPTURED; no reversal/state event. |
| Any uncertain transport/protocol result | Original payment status remains; no new financial effect. |

`localEffectApplied` is therefore true for a declined authorization but false for a declined capture/refund. A confirmed capture/refund decline releases the active-command gate and permits a genuinely new request UUID for another attempt. An uncertain command keeps that gate closed.

## 6. Idempotency, concurrency and fencing

`ProviderOperationRepository` stores immutable merchant/payment/action/scenario/amount/currency/source-version/provider-instance intent. Reusing a request UUID with another payment, action or scenario returns 409. The same UUID under another merchant is independent.

A partial unique index allows one unfinished command per payment, including UNKNOWN and REQUIRES_REVIEW. Row locks and nonwaiting acquisition serialize preparation without leaving partial bindings. PostgreSQL SQLSTATE `55P03` is recognized as a busy conflict even when Spring wraps it as `UncategorizedSQLException`; callers receive 409 and can retry the exact command.

Each claim commits an incremented attempt, random lease token and database-clock deadline. Concurrent exact submissions cannot both claim an unexpired lease. A crashed worker leaves a recoverable IN_FLIGHT record. After expiry, another caller claims a new token and resends the same provider key. A delayed old worker cannot complete using its old token.

This is **fencing**, not proof that a remote request stopped. Two remote invocations can overlap after a lease expires. The sandbox's durable exact-intent idempotency makes them resolve to one decision. The local completion transaction and existing unique journal constraints prevent duplicate local effects. There is no blanket network-level exactly-once guarantee.

The default lease is 30 seconds and request deadline 2 seconds. Validation requires at least a two-second margin; supported bounds are 5–120 seconds for leases and 100–10,000 milliseconds for HTTP. A command has a maximum of 1,000 recorded claims. Exhaustion requires review; this milestone has no administrative reset endpoint.

## 7. Provider binding and guarded local application

The first provider AUTHORIZE on a CREATED payment commits a binding to the configured provider instance. Enrollment is irreversible through the API. It prevents manual authorize/capture/fail/refund from bypassing the provider, including when the first provider result is UNKNOWN. There is no cancel/void workflow yet.

CAPTURE and REFUND require a confirmed provider authorization/reference and matching instance. A manually authorized payment cannot be adopted halfway through. New or retried unfinished work requires provider integration enabled and the original configured instance. Completed historical commands remain readable/replayable while disabled.

The instance UUID identifies the provider journal's namespace, not just a URL. Keeping the same UUID while discarding its journal destroys remote idempotency history. Restore the original journal; assigning the old UUID to an empty store is not a recovery procedure. URL or API-key changes can retain the same namespace only when they still reach that original history.

`PaymentService.applyProviderResult` requires an existing transaction and an operation authorized in that very PostgreSQL transaction. V17 records `application_xid`, and database triggers tie payment changes to the matching command, source version/status and expected target. Deferred checks require the resulting payment/version, outbox and authorization binding to agree at commit. Existing balanced-ledger constraints remain active.

Application checks make normal mistakes readable; database checks protect ordinary direct SQL and bypassed service paths. They do not constrain a privileged database administrator who can disable triggers or rewrite evidence. A source mismatch produces REQUIRES_REVIEW rather than silently overwriting local state. No force-apply tool is provided.

Those guards require a consistent local command/payment/event transaction; they are not cryptographic proof of a remote approval. The application and its database access remain trusted. The adapter trusts the configured local sandbox and validates response correlation; the loopback HTTP demo has no signed response attestation. Verified callbacks need their own boundary in the next milestone.

## 8. HTTP adapter and strict protocol

`PaymentProvider` separates orchestration from transport. `HttpSandboxPaymentProvider` is the only implementation here. Java 21 `HttpClient` avoids adding a vendor SDK before a vendor contract exists. The configured address must be loopback; the sandbox binds IPv4 loopback. Redirects and proxy use are disabled so the local credential is sent directly to the intended endpoint.

Requests use a shared Bearer key and operation UUID header. `ProviderWire` defines a versioned closed JSON contract, caps documents at 8 KiB, and rejects malformed UTF-8, duplicate/trailing/unknown fields, missing data and numeric coercion. Responses must match operation, payment, merchant, instance, action, amount, currency and expected remote references. Transport success alone is insufficient evidence to change money.

`sandboxScenario` is an explicit local test control. A future live adapter must derive approval from the authenticated provider's contract; a merchant-supplied scenario must never become authority to approve a real transaction.

The adapter uses an asynchronous request plus a bounded wait for the complete response body and a capped body subscriber. A server that sends headers but never finishes its body still reaches the application deadline; a test requires refusal within two seconds with a configured 200 ms limit. Invalid/oversized replies, redirects, connection failure and incomplete responses all become sanitized uncertainty.

Java's request timeout and client lifecycle are documented in the official [HttpRequest.Builder](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html) and [HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html) APIs. Our complete-body deadline and response-identity checks are explicit adapter behavior, not assumptions about a real vendor.

## 9. Separate sandbox process and persistent decisions

`java -jar ... provider-sandbox` dispatches before Spring, so it requires no PostgreSQL, Kafka or application credentials. `SandboxProviderServer` exposes only POST `/v1/operations` with the shared key and matching idempotency header. Options are exactly `--port`, `--data-dir` and `--instance-id`; port 0 selects an available test port.

`SandboxProviderStore` holds a single-writer file lock, persists its instance identity and records ordered decisions as separate JSON files. It computes the next remote payment state, force-writes a temporary file, atomically publishes it, then updates memory. Startup validates and replays the complete ordered journal; missing sequence, corruption or another instance is refused. A second process cannot own the same directory concurrently.

The journal supports at most 10,000 decisions, each bounded to 8 KiB. The HTTP server uses four workers; this is a small teaching service without production admission/load guarantees. On unsupported atomic publication it refuses rather than silently choosing weaker writes.

`TIMEOUT_AFTER_ACCEPT` is deliberately named for an uncertain accepted outcome. On a new command the server persists the decision, advertises the complete response length, sends only a short prefix and closes. An exact retry returns the full persisted response. The packaged test kills and restarts the sandbox between those requests, proving recovery across that process boundary.

This demonstrates process-restart persistence on the tested local filesystem. It does not certify sudden power-loss durability, remote filesystems, independent backups or cryptographic authenticity. Java documents the guarantees and possible failures of [atomic file moves](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html) and [FileChannel.force](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html); directory durability and external restore verification remain outside this implementation.

## 10. Important failure cases

| Failure | Durable result and recovery |
| --- | --- |
| Crash after prepare, before claim | READY remains; retry original command. |
| Crash after claim, before/after network | IN_FLIGHT remains; wait for lease expiry and retry original command. |
| Provider accepted, response lost | UNKNOWN; unchanged payment/journal; exact retry recovers one saved provider decision. |
| Invalid identity or money in response | UNKNOWN; no local application. |
| Provider approved, local outbox insert fails | Completion transaction rolls back payment, ledger and operation completion; persisted lease remains. Retry after expiry recovers the remote result. |
| Old worker returns after another claim | Old token ignored; cannot overwrite current progress. |
| New command submitted while unresolved | 409; recover the existing command, do not invent another key. |
| Provider disabled or instance changed | New/unfinished execution refused; history still accessible. |
| Journal missing/corrupt or capacity exhausted | Sandbox refuses work; application treats an unconfirmed reply as UNKNOWN. Preserve evidence and restore correct history. |
| Confirmed provider result conflicts with source | REQUIRES_REVIEW; no automatic local effect, force repair or replacement command. |

Retry is currently driven by the merchant resubmitting the original POST. There is no polling endpoint at the provider, automatic recovery scheduler, webhook receiver or timeout-to-decline conversion.

## 11. Alternatives and why these choices fit this milestone

| Chosen approach | Alternative and tradeoff |
| --- | --- |
| Persist intent before HTTP, finish separately | One long transaction is simpler to read but holds locks and cannot atomically commit with an independent provider. Durable orchestration requires more states. |
| Stable operation UUID plus exact provider replay | New key after timeout might duplicate a remote effect. Safe replay depends on retaining the provider namespace/history. |
| UNKNOWN distinct from DECLINED | Treating all errors as failure gives a tidy response but makes an unjustified financial claim. Uncertainty requires visible recovery work. |
| Lease token plus remote idempotency | A process-local lock cannot coordinate instances/restarts. A lease alone cannot stop an already sent request. Two complementary mechanisms are needed. |
| First-authorization binding | Mixing manual and provider transitions permits local/remote divergence. Binding limits flexibility until void/cancel and reconciliation exist. |
| Java HTTP adapter with a separate local sandbox | In-process mocks miss sockets, reply loss and process restart. A live vendor SDK introduces account/contract work beyond this local milestone. Vendor-specific adapters remain a valid later choice. |
| PostgreSQL durable state and guards | Redis-only command ownership would separate recovery authority from the financial database. Redis may later support risk/rate controls, not replace these committed records. |
| Single-writer bounded file journal for sandbox | In-memory decisions vanish on restart. Another production database would add operational scope to a teaching provider. Files impose capacity, filesystem and single-process limits. |
| Merchant-driven retry now | A queue/scheduler can automate recovery, but needs backoff, ownership, shutdown and operational controls. Explicit retries make the current contract testable first. |
| Reuse existing financial/outbox services | Duplicating capture/refund posting inside the adapter risks inconsistent ledger rules. Shared paths retain earlier invariants but require careful transaction authorization. |

These are contextual choices. A production provider, higher load, multi-region operation or richer payment lifecycle could justify other tools; those alternatives are not universally worse.

## 12. Important files and responsibilities

Paths below are relative to the project root.

| File | Purpose |
| --- | --- |
| `src/main/java/com/fincore/payments/controller/ProviderOperationController.java` | Submit and inspect owned provider commands. |
| `src/main/java/com/fincore/payments/service/ProviderOperationService.java` | Suspend ambient transactions and coordinate prepare/claim/network/finish. |
| `src/main/java/com/fincore/payments/service/ProviderOperationTransactions.java` | Short durable transactions, replay, fencing, outcome application and snapshot reads. |
| `src/main/java/com/fincore/payments/repository/ProviderOperationRepository.java` | Source locks, bindings, command progress and SQL lease/result updates. |
| `src/main/java/com/fincore/payments/dto/ProviderOperation.java` | Command, progress and versioned wire records/enums. |
| `src/main/java/com/fincore/payments/provider/PaymentProvider.java` | Provider execution interface. |
| `src/main/java/com/fincore/payments/provider/HttpSandboxPaymentProvider.java` | Bounded authenticated HTTP transport and uncertainty mapping. |
| `src/main/java/com/fincore/payments/provider/ProviderWire.java` | Strict JSON and matching-request validation. |
| `src/main/java/com/fincore/payments/provider/SandboxProviderServer.java` | Standalone authenticated sandbox and deliberate reply loss. |
| `src/main/java/com/fincore/payments/provider/SandboxProviderStore.java` | Durable exact decisions and reconstructed remote lifecycle. |
| `src/main/java/com/fincore/payments/config/ProviderProperties.java` | Validated opt-in configuration, timing and redacted credential representation. |
| `src/main/java/com/fincore/payments/service/PaymentService.java` | Guard manual paths and apply confirmed provider outcomes through existing ledger/outbox logic. |
| `src/main/resources/db/migration/V17__add_durable_provider_operations.sql` | Bindings, commands, uniqueness, transition and commit-time guards. |
| `src/test/java/com/fincore/payments/integration/ProviderOperationDatabaseTests.java` | Real merchant/CSRF, database, HTTP, concurrency, upgrade and rollback cases. |
| `src/test/java/com/fincore/payments/operations/PackagedProviderSandboxIT.java` | Real executable-JAR process restart/replay and sanitized startup refusal. |

The entry point dispatches the sandbox before Spring. The event-operations CLI explicitly disables provider execution and enabled-provider credential requirements, even when inherited environment variables enable it for an API process. Basic property parsing and timing validation still apply to that Spring-based CLI. Existing disposable database resets include the new child tables; financial assertions remain intact.

## 13. Validation and limits

The [milestone summary](MILESTONE_17_SUMMARY.md) records the final full-run counts and environment. New tests exercise strict configuration/protocols, capped/deadline HTTP, real credential/CSRF ownership, approved/declined/uncertain outcomes, lease recovery, late completion, rollback, database guards, Repeatable Read snapshots and V16 upgrade preservation. Packaged tests exercise actual process restart rather than claiming persistence from an in-process mock.

V17 adds tables/guards without rewriting existing payment, merchant, creation-retry or outbox rows. Upgrade tests compare those before-images and then prepare a new command. Earlier migrations remain unchanged. Coordinate application upgrades: older binaries are not a supported mixed-version mode and cannot safely operate the new managed lifecycle.

No live vendor/card transaction, incoming callback, outgoing merchant notification, partial capture/refund, fee, void, settlement, automated provider recovery, Redis risk control or production-readiness claim is included. `REQUIRES_REVIEW` and exhausted retries require a future controlled resolution contract.

## 14. Holistic impact and next implementation

The layers now connect: authenticated merchant → owned payment → durable provider command → independent provider decision → local payment/ledger/outbox transaction → existing eventual projection delivery. Earlier recovery/routing work still applies to events created by confirmed provider outcomes; it does not replace provider reconciliation or resolve an unknown remote result by itself.

The important new boundary is between two independently committed systems. Local rollback protects our database; stable remote identity recovers the other side. The documentation and tests make that distinction visible instead of assuming a successful socket call and a database transaction form one atomic unit.

**Next logical milestone: 18 — incoming provider webhooks.** Design verified callback identity/authentication, durable receipt and duplicate/out-of-order handling, then integrate events with the frozen provider command and guarded payment application. A callback must not trust a merchant/payment UUID from an unverified payload or apply a second journal. No callback route is delivered in Milestone 17.

Approximately eight proposed blocks remain, 18–25, covering callbacks, merchant notifications, expanded accounting, settlement, Redis/risk, observability, operational closure and final deployment/validation. Count completion against the [finish criteria](ROADMAP.md#finish-criteria), not a percentage based only on milestone numbers.

## TOPICS TO LEARN FROM THIS SESSION

- Provider adapters and protocol contracts
- Distributed transactions and uncertain outcomes
- Idempotency scopes and durable request identity
- Spring transaction propagation and proxy boundaries
- PostgreSQL row locking, partial indexes and SQLSTATE
- Leases, fencing tokens and crash recovery
- Optimistic versions and guarded financial application
- Deferred constraints and transactional outbox reuse
- Java HttpClient deadlines and bounded response bodies
- Strict JSON/UTF-8 and response correlation
- Atomic file publication and process-restart persistence
- Fault injection, concurrency and executable-JAR testing
- Provider webhooks and reconciliation
