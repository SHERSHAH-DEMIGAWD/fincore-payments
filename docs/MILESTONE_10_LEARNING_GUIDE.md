# Milestone 10 — Authenticated projection promotion and rollback

This session connects the isolated rebuild from Milestone 9 to the HTTP read path. Use the [routing runbook](PROJECTION_ROUTING_RUNBOOK.md) for requests, the [session summary](MILESTONE_10_SUMMARY.md) for verification and the [complete guide](COMPLETE_LEARNING_GUIDE.md) for the full project. All behavior here belongs to independent **fincore-payments**.

## Why this block matters

The project has progressed from accepting a payment safely to recovering its asynchronous read model safely. The earlier layers establish different facts: payment state, immutable accounting, delivery intent, accepted consumer events, recorded incidents, comparison results and reproducible rebuild inputs. None alone answers which projection readers should use now.

Milestone 9 could construct a correct shadow and observe agreement. A source payment could then change before somebody switched readers. A one-time pointer change could also strand the selected shadow at its build version. Milestone 10 adds the missing boundaries: an authenticated decision, a fresh comparison protected against concurrent changes, atomic read routing, continued delivery to both targets and a checked return to the baseline.

This remains a learning backend. Routing changes which latest-payment read model is served; they do not capture money, reverse a payment, settle with a bank, or certify complete historical processing.

First promotion requires a healthy, current baseline as well as a healthy shadow. It cannot repair a missing or drifted baseline: accepting unequal targets would weaken the preserved-read and verified-rollback guarantees. This is a controlled view cutover exercise. Repairing a damaged baseline needs a separate explicitly audited workflow.

## The implemented model

| Name | Meaning and lifetime |
| --- | --- |
| `payment-projection-v1` | Permanent baseline receipt and diagnostic identity. It continues processing in every route state. |
| `shadow-<run UUID>` | Generated rebuild target. The first successful promotion enrolls exactly one shadow for that database's route lifetime. |
| `projection_routes` | A singleton pointer plus revision and enrolled run. Before enrollment, absence means baseline at revision 0. |
| `projection_route_audit` | Immutable accepted promotion/rollback decisions, each with request UUID, authenticated actor, reason and revision. |
| Rebuild generation | Frozen historical input/progress evidence. Enrollment freezes further changes to this run's rebuild metadata. |

After enrollment, `PaymentEventConsumer.consume` applies each valid event to baseline and enrolled shadow in one database transaction, even when the baseline receipt already exists. Version guards retain the newest full snapshot. The targets' latest projection and canonical receipt must agree for the affected payment; a discrepancy aborts processing and follows the existing event-conflict diagnostic path.

Rollback changes the pointer to the baseline and keeps the same shadow enrolled and receiving events. Re-promotion can select that same maintained shadow. A second shadow replacement, retirement and unbounded fanout are deliberately outside this block. An isolated additional rebuild can still be created, but it cannot replace the enrolled target through this API.

## Freshness is checked inside the decision

`ProjectionRoutingService.promote` owns a `REQUIRES_NEW` / Read Committed transaction with a 30-second timeout. Rollback owns the same boundary; either operation suspends any caller transaction instead of inheriting its isolation or old snapshot:

1. Validate the bounded command identity, revision, principal and reason.
2. Acquire the database-wide exclusive transaction advisory fence without waiting; acquire the relevant source, consumer, rebuild and incident tables in `SHARE` mode with `NOWAIT`.
3. Look for an accepted request with exactly matching action, actor, reason and preconditions. Exact replay returns its original result; changed intent conflicts.
4. For a new command, check the current route revision, configured source/topic, run identity and expected build generation. First enrollment requires `BUILT`, no blocked/pending inputs and fresh frozen-manifest/source/shadow agreement.
5. Require both targets to agree with current local source/outbox facts and each other, including source-less latest consumer records. Require zero known open failures/quarantines across source scopes for this logical consumer.
6. Commit enrollment/pointer revision and immutable audit together.

Rollback uses the same fence and current safety comparisons before selecting the baseline. It does not assume that an older target is safe merely because it was once active.

Read Committed is useful here because comparisons start after every required table lock is held and see the latest committed state. Consumer and rebuild mutation paths acquire the shared advisory gate. Source and incident writers are additionally covered by the table locks. `NOWAIT` avoids waiting in cycles with existing source/position lock order: a busy decision returns 409 and its transaction releases acquired locks. Normal activity can resume after commit.

An isolation setting on a default `REQUIRED` service method does not replace the isolation of an already active caller transaction. Without explicit ownership, an older Repeatable Read caller could make fresh-looking comparisons use its old snapshot. `REQUIRES_NEW` prevents that for routing. Its accepted decision commits independently: a later rollback of the suspended caller does not undo the route. A caller retaining conflicting database locks can still make the nonwaiting switch return a busy conflict. See [Spring 6.2 transaction propagation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html) for participation and independent transaction semantics.

Live consumption deliberately keeps `REQUIRED` so `ConsumerRecordProcessor.process` can commit its position lock/success resolution, receipts and both projections together. Starting an independent consumer transaction would break that atomicity. Instead, `PaymentEventConsumerRepository.requireReadCommitted` reads the **actual JDBC connection isolation** before receipt/projection writes and rejects an inherited Repeatable Read/Serializable transaction. This prevents an old consumer snapshot from missing a newly committed enrollment and skipping fanout; it does not rely only on annotation metadata.

The advisory gate is cooperative application coordination; it is not a database permission boundary. Table locks and constraints add persisted protection, while administrators with privileged SQL remain outside the HTTP operator threat model. The conservative table freeze briefly conflicts with writes and can scan the complete view; production-volume latency and starvation under sustained writes have not been established. [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html) and [advisory lock functions](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS) explain the underlying mechanisms.

## Enrollment versus an old planning snapshot

A subtle race arises because rebuild planning uses Repeatable Read. A catch-up transaction could establish its snapshot before enrollment, wait for the shared gate, and otherwise fail to see the newly inserted route afterward.

Enrollment therefore also updates `projection_rebuild_runs.routing_enrolled`. When the stale planner locks that changed run row, PostgreSQL forces serialization failure. The existing whole-transaction retry obtains a fresh snapshot and then rejects mutation of the enrolled run. Database guards freeze its run, generations and items after enrollment; a route and its enrollment flag must commit together.

This is why checking only an application boolean or querying the route once is insufficient. The changed row creates a database conflict on the same object that rebuild mutation already locks. Retrying only the final statement would retain the stale transaction snapshot; the whole planning transaction must restart.

## What readers and writers observe

`PaymentProjectionController.getProjection` still calls `PaymentEventConsumer.getProjection`. `PaymentEventConsumerRepository.findRoutedProjection` selects the active identity and projection in one SQL statement. The query sees one committed snapshot, so it cannot read a pointer in one statement and a different target state in another. A request overlapping a committed cutover can observe the complete old or new committed view.

Promotion establishes agreement at its transaction boundary. It does not make subsequent payment writes synchronously update either projection. A capture still commits payment, ledger and outbox first; a later valid consumer transaction advances both read models. The HTTP projection may lag the authoritative payment in the meantime, as before.

`LOCAL` delivery still writes only `local_payment_event_inbox`; it does not run the Kafka projection consumer. Routing cannot make LOCAL delivery maintain a projection. A missing target or missing receipt is not repaired by deleting deduplication records or forcing a pointer change.

Once enrolled, a shadow's live data can advance beyond its frozen manifest. `rebuild-validate` remains a comparison against those historical inputs, so later live facts can produce shadow/source drift there. Its promotion metadata identifies `ENROLLED_LIVE_FANOUT`; an isolated target reports `REQUIRES_AUTHENTICATED_PROMOTION`. Use route status for active ownership and the routing transaction's current checks for new switches. Rebuild `BUILT` remains historical evidence, not a continuously current certificate.

## Authentication, authorization and CSRF are separate

`ProjectionOperationsSecurityConfiguration` protects only `/api/v1/operations/projection-routing` and its children. The servlet application accepts one explicitly configured operator username and a raw BCrypt hash, assigning `PROJECTION_OPERATOR`. With neither configured, this surface denies access. Partial credentials or invalid configured values fail servlet startup instead of falling back to an accidental usable account. The headless CLI does not load this servlet-only configuration.

The controller derives `actor` from `Principal.getName()`. A request's reason documents intent; a caller-provided name would only be a claim. The configured username rejects `:` because it separates username from password in Basic credentials. Existing CLI `--operator` labels remain local audit declarations governed by local execution/database access. These are separate boundaries.

HTTP Basic authenticates the credential on requests; it does not encrypt transport. Use TLS outside the loopback learning setup. The configured BCrypt value is a one-way password verifier, not the plaintext password clients send and not a `{bcrypt}`-prefixed delegating-encoder value. Do not commit credentials or paste them into examples. [Spring's password storage reference](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) describes the adaptive verifier tradeoff.

CSRF remains enabled for this operator surface. Obtain a token from authenticated `GET /csrf`, retain its session cookie and send its returned header/token with POST. A valid password alone does not satisfy that request boundary. Session loss or application restart may require a new token. [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html) documents session-backed tokens and the request flow; [HTTP Basic](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/basic.html) documents authentication processing.

The public learning payment API remains unauthenticated. This scoped operator boundary is not a complete production security system: multi-user administration, federated identity, credential rotation workflow, rate limiting, managed TLS and distributed sessions remain separate work.

## Three identities protect different retries

| Identity | Protects against | Exact meaning |
| --- | --- | --- |
| Event UUID / payment version / logical consumer | Repeated or conflicting event processing | Durable canonical receipt and version-guarded projection. |
| Routing request UUID | Lost response followed by a command retry | Same authenticated actor, action, reason and original expected values return the original accepted result. |
| Route revision / expected build generation | An operator acting on outdated state | New intent must match the route now and the intended frozen build. |

An accepted promotion at revision 1 may be retried after rollback to revision 2. It returns its original revision-1 result with `replayed=true`; it does not promote again. Read status separately for the current route. Because replay still takes the fence, contention can return a temporary 409 even for an accepted request. Retain the exact body/request UUID and retry after the contention is resolved.

Changing credentials to a different username also changes actor identity. An old accepted request cannot be replayed as a different actor. A new decision needs a fresh UUID and current revision.

HTTP revision and build-generation counters require explicit integral JSON numbers within their supported range. Omitted/null values, numeric strings, decimals and overflow are rejected rather than coerced to a default or rounded. This keeps the wire request's preconditions explicit.

## Why these approaches fit this stage

| Chosen approach | Alternative considered | Why chosen here; cost retained |
| --- | --- | --- |
| Fresh comparisons and a transactional fence | Trust the last validation response or check only versions | Eliminates the check/switch window and compares content/evidence. Full scans and brief write exclusion limit scalability. |
| Persistent singleton route | Process-local flag or an environment variable flip | All instances/readers share a committed decision and audit. Routing now depends on database schema/query correctness. |
| One SQL read | Read route, then query projection separately | One statement snapshot avoids a mixed pointer/data observation. It does not make multiple HTTP requests a frozen snapshot. |
| Keep baseline and one shadow warm | Stop the old target, or fan out to every historical target | Supports checked rollback while bounding live targets. It doubles applicable receipt/projection work and does not support target replacement. |
| Freeze enrolled rebuild metadata | Continue catch-up batches against a live target | Keeps build checkpoints distinct from ongoing delivery and prevents stale planners changing ownership. New rebuilds need separate runs. |
| Routing owns `REQUIRES_NEW`; consumer joins `REQUIRED` and checks actual isolation | Rely on isolation annotations, or start independent transactions everywhere | Routing gets a fresh authoritative decision; consumer retains position/success atomicity. Independent decisions consume another connection when nested and may conflict with caller-held locks. |
| Request UUID plus revision and audit | Blindly retry POST, or use revision alone | Distinguishes a lost accepted response from new intent. Audit/evidence require a future retention policy. |
| Scoped Spring Security, BCrypt and Basic | Self-declared operator; custom password filter; OAuth/OIDC now | Provides a framework-backed authenticated principal for one bounded learning surface. Federated identity and multiple operators would justify a richer boundary. |
| CSRF token and session | Disable CSRF because requests use Basic | Preserves protection for browser-usable credentials. Clients must retain token/session state. |
| Shared transaction fanout | Independent consumer groups or distributed transactions | Both co-located targets commit or roll back together. Independently deployed/materialized targets would need a different ownership/cutover protocol. |

These alternatives are not universally worse. Independent consumers, a high-water-mark protocol, leases or federated authentication can fit other scale/deployment requirements. This implementation favors an explicit, inspectable correctness boundary in the existing single application/database architecture; it does not claim measured production performance.

## Concrete failure cases to explain

- Capture commits after a standalone rebuild validation: first promotion detects changed source or missing current receipts and conflicts until ordinary processing and an appropriate pre-enrollment catch-up restore agreement.
- A consumer owns the shared fence when promotion arrives: promotion returns 409 without switching. The consumed fact can commit normally; the operator retries the same intent after reviewing status.
- One warm target has a receipt/content/projection conflict: fanout rolls back and normal consumer failure handling retains evidence. Promotion/rollback cannot hide it.
- A source-less consumer record exists only in baseline: target equivalence fails, even though all local source payments match. The API offers no arbitrary-copy or forced-cutover bypass.
- An old Repeatable Read caller invokes promotion/rollback after a newer source commit: the independent Read Committed decision sees it and rejects stale readiness. Live consumption under incompatible inherited isolation fails before receipt/projection writes instead of missing enrollment.
- Audit insertion or a deferred consistency check fails: route, enrollment and decision roll back together.
- Promotion commits but its response is lost: exact command replay returns the original result; a changed actor/reason/request intent conflicts.
- Rollback happens after a later valid event: both maintained targets already include that event; rollback checks freshness again and changes reads without undoing source or accounting facts.

## A test-resource lesson

Spring's cached test application contexts can keep separate Hikari connection pools alive. Eleven contexts with the default ten-connection pools exceeded the local PostgreSQL 100-client limit before packaged-CLI tests could connect. `src/test/resources/application.properties` now caps each test pool at five connections with `minimum-idle=0`. This bounds test resource use instead of raising the production database limit or changing application defaults. A connection-budget failure is distinct from a routing invariant failure; repeated contexts and subprocesses must fit the same test database's capacity.

## Code reading path and next work

Read `ProjectionRoutingController` → `ProjectionOperationsSecurityConfiguration` → `ProjectionRoutingService` → `ProjectionRoutingRepository` → `V10__add_projection_routing.sql`. Then follow `PaymentEventConsumer.consume`, `PaymentEventConsumerRepository.findRoutedProjection` and the rebuild shared-gate/enrollment guards. The database and MVC suites test the distinct transaction and authentication boundaries; actual run results belong in the [summary](MILESTONE_10_SUMMARY.md).

The next logical block is controlled target replacement/retirement: define how a new isolated target catches live changes, adopts coverage, replaces the one enrolled target and retires old state without losing replay or audit guarantees. Pair that with measured operating limits and readiness evidence before adding orchestration. Retention, full merchant security, real provider integration and settlement remain later objectives.

## TOPICS TO LEARN FROM THIS SESSION

- Projection promotion, rollback and reader routing
- Shared and exclusive PostgreSQL advisory transaction locks
- Table locks, NOWAIT and deadlock avoidance
- Read Committed versus Repeatable Read snapshots
- REQUIRED versus REQUIRES_NEW and actual JDBC isolation
- Serialization conflicts and whole-transaction retry
- Compare-and-set revisions and idempotent operator commands
- Atomic fanout, receipt identity and warm rollback targets
- Deferred constraints and immutable decision audits
- Spring Security filter chains, principals and role authorization
- BCrypt password verifiers and HTTP Basic over TLS
- CSRF tokens, sessions and authenticated HTTP testing
- Cached test contexts, Hikari pools and connection budgets
- Latest-state equivalence versus historical completeness
