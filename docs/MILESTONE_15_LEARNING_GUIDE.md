# Milestone 15 learning guide — Approval lifecycle and independent file verification

**Current continuation (2026-10-25):** [Milestone 16](MILESTONE_16_LEARNING_GUIDE.md) adds merchant authentication, immutable payment ownership, tenant-scoped retries and financial/event reads. Payment HTTP endpoints now require merchant credentials and POST CSRF; older public-API descriptions are historical. Use the [merchant runbook](MERCHANT_ACCESS_RUNBOOK.md) and [bounded completion roadmap](ROADMAP.md).

Implemented 2026-10-25 in the independent `fincore-payments` project, continuing [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md). Use the [lifecycle runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md) for commands and the [session summary](MILESTONE_15_SUMMARY.md) for test evidence. The [complete Milestones 1–10 guide](COMPLETE_LEARNING_GUIDE.md) remains the foundation and historical snapshot.

## 1. The problem this milestone solves

Milestone 14 copied every artifact named by a bounded retired-target plan and recorded which authenticated operator acknowledged one exact copy. Two gaps remained: an accepted approval had no time limit or withdrawal mechanism, and checking a downloaded file required application-side verification or a manual whole-file checksum.

Milestone 15 gives new approvals a fixed lifetime, adds immutable revocations, and provides a packaged command that verifies a downloaded bundle against independently trusted metadata and its original plan. These are evidence controls. Every result still has `deletionPermitted=false`; physical deletion and restore/import remain unimplemented.

**Example:** an operator approved a copy at 10:00 with a 60-minute lifetime. At 10:30 they discover the external storage location is unsuitable and revoke that approval. The original decision remains retrievable, an exact retry returns that same decision, and a fresh review reports `APPROVAL_REVOKED`. At 11:00 the same review also reports `APPROVAL_EXPIRED`.

## 2. How this fits the whole project

| Milestones | Contribution to the platform |
| --- | --- |
| 1–3 | Payment lifecycle, retry-safe creation and balanced immutable ledger postings. |
| 4–5 | Transactional outbox, asynchronous delivery and receipt-based consumer deduplication. |
| 6–8 | Audited recovery, quarantine/replay and read-only comparison of source and derived data. |
| 9–11 | Isolated rebuilds, controlled routing, maintained targets and explicit retirement. |
| 12–14 | Evidence inventory, operating bounds, frozen plans, complete preservation copies and attributed approval. |
| 15 | Time-bounded approval, withdrawal without erasing history, and verification outside the running application. |

The growing operational flow is build → maintain → retire → inventory → plan → preserve → independently check → review → expire or revoke. It gives future retention work explicit evidence and decision boundaries. It does not add payment providers, real funds transfer, settlement, full merchant authorization or production readiness.

Recent work matters because derived projections can be rebuilt and replaced, while their operational history must remain understandable. A correct financial write path alone cannot answer which retired view existed, what evidence was retained, who reviewed it, or whether a copied file still matches that evidence.

## 3. Historical acceptance and current validity

`ProjectionPreservation.Approval` remains an immutable decision. Its new `validityMinutes` and `expiresAt` fields describe the policy accepted with that decision. `ProjectionPreservationService.reviewApproval` separately observes whether it is current.

| Operation | Meaning |
| --- | --- |
| Approval metadata GET | Returns the accepted decision, including its original validity interval. |
| Exact approval POST retry | Recovers that same accepted decision; it does not renew it. |
| Fresh approval review GET | Checks the plan, copy, observed time and committed revocation in one snapshot. |
| New approval request UUID | Creates new intent after all current new-approval checks pass. |
| Revocation POST | Appends withdrawal of one particular approval. |

**Chosen approach:** retain history and compute current validity separately. Mutating an approval status or deleting its row would erase or rewrite what the operator originally accepted. Making a retry fail merely because time passed would make a lost HTTP response ambiguous. Exact replay preserves the existing idempotency contract; a caller needing current validity must request a fresh review.

**Cost:** an accepted response alone cannot be treated as present authority. Every future action that depends on approval must implement a fresh, correctly fenced decision of its own. There is no such executor in this release.

## 4. Snapshotting a bounded approval lifetime

`ProjectionApprovalProperties` binds `FINCORE_PROJECTION_APPROVAL_VALIDITY_MINUTES`. Its default is 60 minutes and the allowed range is 1–1440; invalid configuration fails validation during Spring startup.

For a new approval, `ProjectionPreservationService.approve` captures the PostgreSQL observation time once, normalizes it to PostgreSQL microsecond precision, checks the plan and copy, and stores:

```text
approvedAt = captured observation time
validityMinutes = configured duration at acceptance
expiresAt = approvedAt + validityMinutes
```

The repository writes all three values together. Changing configuration to five minutes tomorrow affects newly accepted approvals, not yesterday's stored interval or its replay.

Using the database clock keeps this decision on the existing evidence/retirement time base; JVM clocks could differ between application instances. The interval starts at the captured observation before checks, so processing time consumes part of it. Database time can still jump: the uncertainty blocker is conservative handling of an observed reversal, not proof that time is globally correct.

At review, time before `approvedAt` yields `APPROVAL_TIME_UNCERTAIN`; time equal to or after `expiresAt` yields `APPROVAL_EXPIRED`. Expiry is inclusive at the boundary. Other plan/copy blockers remain in effect even within the interval. There is no scheduler that updates rows when a clock reaches the deadline.

**Alternatives and tradeoffs:** calculating expiry from the current configuration on every read would retroactively change accepted policy. An operator-supplied expiry would widen the command contract and allow inconsistent intervals. An expiry-update job adds mutable state, scheduling and recovery concerns for a condition that can be derived. A fixed stored interval is simpler here, but depends on trustworthy database time and does not itself serialize a future consequential action.

## 5. Upgrading old approvals without inventing history

`V15__add_approval_validity_and_revocation.sql` adds nullable validity columns with no default or backfill. Existing V14 rows retain their prior audit fields and both new fields remain null. A fresh review reports `APPROVAL_VALIDITY_UNSPECIFIED` for them. An exact accepted retry still recovers the legacy decision, and it can be revoked.

For new inserts, a separate trigger requires explicit validity. A check constraint requires 1–1440 minutes, finite timestamps and the exact arithmetic relationship. Both partial-null combinations are rejected. Existing update/delete immutability guards remain in place; old migrations are unchanged.

**Why this choice:** assigning a new expiry to old approvals would claim a policy the operator never accepted. Rewriting V14 would break applied migration history. Making the new columns globally non-null would force a fabricated backfill. Nullable historical data plus a new-insert guard expresses the actual compatibility rule, at the cost of handling legacy approvals explicitly in reads and reviews.

This is additive schema compatibility, not a mixed-version deployment guarantee: an older binary cannot insert a new approval after V15 because it omits the required validity fields.

Approval JSON responses also gain `validityMinutes` and `expiresAt`; clients with closed response schemas need to accept them, including null values for legacy decisions. The existing approval request shape is unchanged.

## 6. Revocation is a new immutable fact

`ProjectionApprovalLifecycleController.revoke` receives only a request UUID and bounded single-line reason. The actor comes from the authenticated principal. Existing `PROJECTION_OPERATOR`, Basic/BCrypt and POST CSRF controls apply; absent operator credentials leave the boundary closed.

`ProjectionApprovalLifecycleService.revoke` owns a new Read Committed transaction with a 30-second timeout. It validates attribution, acquires a dedicated nonwaiting advisory transaction lock, checks the approval's configured source/topic scope, recognizes exact replay, rejects conflicting intent or another revocation, and inserts the audit.

`projection_approval_revocations` has a request UUID primary key, a unique approval UUID foreign key, database-recorded time, actor and reason. Update/delete triggers protect the record. There is one revocation per approval, no unrevoke, no automatic withdrawal of every approval for the same plan, and no deletion of its preserved copy.

**Example:** request R revokes approval A. Repeating R/A with the same actor and reason returns the original with `replayed=true`. Reusing R for another approval or reason conflicts. A new request attempting to revoke A again also conflicts; retrieve A's existing revocation instead.

**Alternatives:** a mutable `revoked` flag loses the original withdrawal attribution and request replay contract. Reusing approval rows for cancellation changes their meaning. Revoking an entire plan would silently affect separately accepted decisions. The separate table keeps the unit of withdrawal explicit and adds one scoped lookup during fresh review.

## 7. Why withdrawal uses its own lock

Granting approval requires current route/plan/copy checks under the existing routing fence. Withdrawal requires identity and scope, even if that evidence has since become stale or damaged. Revocation therefore uses advisory key `742190311`, separate from routing key `742190310`, and does not freeze routing tables or load the preservation body.

An expired approval or an approval whose stored copy no longer verifies can still be revoked. A held routing advisory fence does not itself prevent revocation. Concurrent application revocation requests are serialized by the dedicated nonwaiting lock; a busy request gets a conflict and can retry its exact intent. PostgreSQL uniqueness also prevents two revocation rows for one approval.

**Alternatives:** requiring a current route revision or verified copy to revoke would make withdrawal unavailable precisely when those conditions fail. Sharing the routing lock would create unnecessary coupling. Ordinary row locking or finer locks could increase concurrency, but would need an ordering protocol for both request identity and approval identity. The coarse dedicated lock favors straightforward correctness at low operator volume; it serializes revocations across the configured database and can refuse unrelated simultaneous withdrawals.

This is a logical separation, not a guarantee of zero waiting under every raw SQL/DDL lock. No lock coordinates a future deletion action yet.

## 8. One snapshot for a fresh review

`ProjectionPreservationService.reviewApproval` retains its new read-only Repeatable Read transaction. Its database observation, plan state, copy and revocation read share one snapshot. A revocation may commit while the report is being produced: that report can still describe the earlier snapshot, while the next review sees withdrawal.

The lifecycle blockers added here are:

- `APPROVAL_VALIDITY_UNSPECIFIED` — legacy approval has no accepted interval.
- `APPROVAL_TIME_UNCERTAIN` — observed time precedes acceptance.
- `APPROVAL_EXPIRED` — observed time reached the saved deadline.
- `APPROVAL_REVOKED` — a revocation is visible in this snapshot.

Existing route/policy/target/artifact and copy-binding/integrity blockers are combined with these. No blockers still means `CURRENT_APPROVAL_REVIEW_ONLY`, with `deletionPermitted=false`.

**Tradeoff:** a coherent observation is easier to interpret than separate reads that could mix times, but it is immediately historical. Repeatable Read is useful for reports; a future executor needs its own fresh concurrency protocol rather than trusting this earlier response.

## 9. Verifying a file without starting the service

`FincorePaymentsApplication.main` recognizes `verify-preservation` as the first argument before calling Spring. It dispatches to `PreservationFileVerifierCli.run`, which creates a strict JSON mapper and the shared `ProjectionPreservationVerifier` directly. No Spring context, database connection, migration, Kafka client or operator account is initialized.

```powershell
java -jar target/fincore-payments-0.0.1-SNAPSHOT.jar verify-preservation `
  --bundle=projection-preservation.json `
  --metadata=preservation-metadata.json `
  --plan=original-plan.json
```

The three files are different inputs: the downloaded exact bundle bytes, independently trusted preservation metadata, and the independently trusted original saved plan. Pass the metadata/plan objects returned by their GET endpoints, not the wrapper object from their POST responses. The runbook covers retaining those references.

Verification checks exact UTF-8 file length and whole-file SHA-256; version/envelope/metadata/plan agreement; every body digest; and exact ordered kind/identity/digest agreement with the original manifest. It reuses the M14 verifier so server and standalone checks share the integrity contract.

**Alternatives:** a shell checksum is useful but cannot alone compare row scope and metadata with the original plan. An HTTP verify endpoint would depend on the same application/database being available. A second independently rewritten verification algorithm could diverge. Reusing the packaged implementation keeps one contract, but means a trusted Java runtime and trusted verifier JAR are part of the checking environment.

## 10. Strict input handling and bounded resource use

The CLI reads at most 8 MiB plus one byte for the bundle and 1 MiB plus one byte for each reference, refusing overflow. It decodes UTF-8 with malformed/unmappable input rejected. Format v1 rejects duplicate keys, trailing JSON values, unknown fields, missing/null constructor fields, coerced scalar values, fractional/exponent integer encodings and numeric timestamps. Instants use ISO-8601 strings. Exactly three unique named path options are required.

**Example:** changing `artifactCount` from `1` to `"1"` or adding a second identical `formatVersion` key is refused even if a permissive JSON reader could interpret it. Appending a newline to the bundle changes the exact file digest. Metadata and plan formatting may vary within the strict JSON contract because their trusted values, rather than their own byte hashes, are compared.

**Why strict parsing:** a preservation check should not silently repair or reinterpret malformed evidence. Unknown-field rejection intentionally makes v1 closed; adding a field later needs a deliberate compatibility/version decision. The limits bound admitted file data, not every allocation or peak memory cost. This command accepts local paths, not storage URLs, and imposes no general storage sandbox.

On success, one JSON report returns `VERIFIED_OFFLINE_NO_DELETION_AUTHORITY`, plan/copy IDs, digest, byte count, artifact count, `databaseContacted=false` and `deletionPermitted=false`, exit code 0. Any handled refusal returns a generic JSON failure and exit code 2. File paths, bodies, actor/reason and exception traces are not printed. Sanitization protects sensitive evidence, while deliberately sacrificing detailed command-line diagnostics.

## 11. Integrity, authenticity, durability and restoration

These are different claims:

| Claim | What this milestone establishes |
| --- | --- |
| Integrity | These supplied bytes match these trusted metadata/plan values at this observation. |
| Authenticity | Trust must come from the independently retained references and trusted verifier; no signature or external attestation is added. |
| External durability | Not established by opening one matching local file. Storage lifecycle, redundancy and future readability are outside the checker. |
| Restore readiness | Not established. There is no import procedure or successful restore drill. |
| Current approval validity | Requires a fresh server review; the offline CLI does not read revocations, route state or current policy. |
| Permission to delete | Never granted. |

If an attacker replaces the bundle, metadata and plan together, a self-consistent set can pass. Extracting a plan from the same untrusted bundle and calling it independently trusted does not establish provenance. A saved successful console report is informational; the server neither receives it nor treats it as authenticated evidence.

Signatures, object storage, retention policies and restore drills are plausible later controls. They require key/credential management, trust rules, failure handling and independent observations. None is universally superior by itself; each addresses a different missing claim.

## 12. Actual request and command flows

```text
POST /api/v1/operations/projection-routing/approvals/{approvalId}/revoke
→ ProjectionApprovalLifecycleController.revoke
→ ProjectionApprovalLifecycleService.revoke
→ ProjectionApprovalLifecycleRepository.fence / request / revocation / revoke
→ PostgreSQL projection_approval_revocations
→ ProjectionApprovalLifecycle.Result
```

```text
java -jar ... verify-preservation --bundle=... --metadata=... --plan=...
→ FincorePaymentsApplication.main
→ PreservationFileVerifierCli.run
→ strict local file reads
→ ProjectionPreservationVerifier.verify
→ sanitized JSON report + process exit code
```

The revocation path also reads the approval and `ProjectionPurgePlanService.scoped` to enforce configured ownership. Payment, ledger, outbox, offsets and target bodies are unchanged.

## 13. Verification strategy and practical failures

The new tests cover HTTP principal/role/CSRF and validation, configuration bounds, expiry boundaries and policy snapshots, exact historical replay, immutable revocations, scope/conflicts/lock contention, concurrent withdrawals, late rollback, unchanged unrelated tables, coherent concurrent review and a real V14→V15 upgrade with legacy replay and new decisions.

Offline tests cover Unicode bytes, changed/truncated/newline files, wrong references, recomputed file digests with changed bodies, parser ambiguity, invalid encodings, size limits, option failures and sanitized output. Packaged process tests use invalid Spring configuration and unreachable infrastructure to prove early dispatch; altered bytes and missing arguments fail without starting Spring.

Consult the [summary](MILESTONE_15_SUMMARY.md) for the final one-run totals and environment limits. Earlier milestone totals remain historical; do not sum focused runs with the full run.

Final Java 21 Maven verification passed 798 executed checks with zero failures/errors: 475 unit/MVC/configuration/CLI, 308 real PostgreSQL, seven native Kafka and eight packaged CLI. Docker-dependent checks skipped: 317. The final three-case packaged verifier rerun passed after portable executable selection; it is not counted twice. Actual local PostgreSQL 18.6 and Kafka broker 3.9.1 were tested; container PostgreSQL 17.6, Linux execution, production load, multi-broker behavior and external restore/durability remain unverified.

## 14. What comes next

The next proposed milestone is authenticated, durable evidence of external verification and restoration: bind a verification observation to the exact plan/copy/digest, define its trusted actor/verifier/storage identity, and record a concrete restore drill with explicit failure/retry semantics. A server record of someone's claim still must not be confused with independent proof of durable storage.

Before any bounded purge executor, design what must remain permanently addressable, which artifacts can be removed, how concurrent approval/revocation/route changes are serialized, and how partial work resumes without breaking historical retries. No executor is authorized by an approval or CLI success in this release.

## TOPICS TO LEARN FROM THIS SESSION

- Immutable audit records and current validity
- Idempotent command replay
- Approval TTL and policy snapshots
- PostgreSQL timestamp precision and clock uncertainty
- Additive Flyway migrations and legacy compatibility
- Check constraints, triggers and foreign keys
- Advisory transaction locks and contention
- REQUIRES_NEW and transaction isolation
- Repeatable Read snapshots
- Principal identity, roles and CSRF
- Strict JSON parsing and UTF-8 byte integrity
- SHA-256 manifests and trust roots
- Bounded file reads and sanitized CLI errors
- Executable JAR dispatch and process tests
- Integrity versus authenticity, durability and restore readiness
