# Milestone 14 learning guide — Verified preservation exports and authenticated review approval

**Current continuation (2026-10-25):** [Milestone 15](MILESTONE_15_LEARNING_GUIDE.md) adds fixed approval expiry, immutable authenticated withdrawal and independent downloaded-file verification. Follow the [lifecycle/verification runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md). Earlier milestone descriptions retain their historical scope; deletion remains unavailable.

Implemented 2026-10-24 in the independent `fincore-payments` project. This continues the [Milestone 13 guide](MILESTONE_13_LEARNING_GUIDE.md). Use the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md) for requests and the [summary](MILESTONE_14_SUMMARY.md) for final verification evidence.

## 1. The problem after Milestone 13

Milestone 13 froze a bounded list of retired-target identities and fingerprints. That list can detect drift, but a hash cannot reconstruct an event payload, a manifest or a projection. Before considering future retention changes, we need a complete copy of the named evidence and a record of what an authenticated operator reviewed.

Milestone 14 saves a versioned preservation bundle containing the original plan and every named artifact body. It verifies each body against the plan, stores the exact issued JSON bytes and their whole-file SHA-256, provides an authenticated download, and records an explicit preservation-review approval tied to that copy.

**The copy is initially stored in the same PostgreSQL database.** It is a preservation/export artifact, not an independent backup. An operator can download it to separate storage, but the server cannot prove that an external copy exists, remains durable or has been restored successfully. Approval records an acknowledgement, not proof of those external facts.

Every metadata/approval/review response still reports `deletionPermitted=false`. There is no delete executor, relaxed evidence trigger, automatic retention job or restore/import API.

## 2. How the recent milestones fit together

| Layer | What it contributes |
| --- | --- |
| Payments, idempotency and ledger, Milestones 1–3 | Financial state and obligations that remain outside this cleanup workflow. |
| Outbox, Kafka and receipts, Milestones 4–5 | Durable delivery and deduplication facts that cannot be casually discarded. |
| Operations, recovery and reconciliation, Milestones 6–8 | Tracked incidents and evidence of agreement/gaps; historical completeness remains uncertified. |
| Rebuild and routing, Milestones 9–11 | Independent targets, controlled cutovers, permanent identities and explicit retirement. |
| Retention visibility and planning, Milestones 12–13 | Review age, bounded intent, artifact fingerprints and freshness checks. |
| Preservation and approval, Milestone 14 | Recoverable artifact bodies and attributed review of one exact exported copy. |

The operational lifecycle is becoming more explicit: build → maintain → retire → inventory → plan → preserve → review. This does not make the payment service a completed funds-transfer or settlement platform. Provider integration, complete merchant security, production capacity and operational readiness remain separate milestones.

## 3. What a preservation bundle contains

`ProjectionPreservation.Bundle` has format version 1, preservation request UUID, the exact saved `ProjectionPurgePlan`, preservation observation time, authenticated actor, reason and a sorted record list. Each `ExportRecord` has artifact kind, scoped identity, SHA-256 and `body`.

The body is the PostgreSQL `to_jsonb(row)::text` representation used by the original inventory, rendered with UTC timestamp settings. It includes full row data. A receipt body therefore includes its retained payload text; a manifest includes source snapshots, original/canonical payloads and progress facts. Run, generation and related routing rows are included too. Baseline receipts/projections, source payments/outbox, ledger and recovery tables are outside this target's export scope. Canonical receipts remain canonical; the export does not recreate unretained Kafka wire bytes or missing historical events.

The body is stored as a JSON **string inside the bundle**, rather than reparsed and rewritten as an embedded object. That preserves the exact row text on which the plan's fingerprint was computed, including representations of numbers and timestamps. Original TEXT payload values survive as fields within that row representation.

**Example:** a receipt contains a merchant reference with `₹` and an emoji. Its UTF-8 byte count differs from its Java string length. The exported receipt's `payload` value must equal the retained database payload exactly; comparing character counts would not verify file length or integrity.

**Alternatives:** exporting only IDs/hashes cannot reconstruct evidence. Exporting selected generations would omit retained historical manifests. Rewriting bodies through an application DTO could omit columns or change the bytes being compared. A PostgreSQL dump could preserve a wider database state, but would not by itself express this plan's bounded scope and operator intent. The bundle is deliberately scoped evidence, not a database backup or a ready-to-run restore script.

## 4. Verification happens at two levels

`ProjectionPreservationVerifier` checks:

1. The saved file length and SHA-256 agree with its exact UTF-8 bytes.
2. Format version, preservation UUID, plan, actor, reason and timestamp match the saved metadata.
3. The record count equals the plan's artifact count.
4. Every body hashes to its declared row digest.
5. The ordered kinds, identities and row digests equal the original plan manifest exactly.

Missing, duplicate, substituted, reordered or altered records cannot satisfy the manifest comparison. Recomputing the whole-file hash after tampering does not make changed row bodies agree with the original plan.

The verifier runs before a preservation insert, before a new approval, and before serving a download. Approval review also verifies the stored copy. A metadata GET alone is not a fresh integrity check. New downloads and approvals fail if integrity verification fails; an existing approval review reports `PRESERVATION_INTEGRITY_FAILED`.

**Alternatives:** a whole-file checksum alone detects accidental corruption against a trusted expected digest, but cannot establish agreement with the earlier plan if both content and digest are replaced. Row checks alone do not protect bundle metadata or the exact downloadable file. Both checks are needed for the implemented contract.

SHA-256 provides comparison evidence, not a digital signature, independent provenance or certified preservation. These row hashes depend on the schema and PostgreSQL's JSON representation. Schema/representation changes may require a fresh plan or format version. A database administrator who bypasses triggers or replaces all trusted records remains outside the application's ownership assumptions.

## 5. Why the exact serialized file is stored as TEXT

V14 stores the generated `export_json` as TEXT, alongside byte length and SHA-256. Download returns those stored UTF-8 bytes directly through `ResponseEntity<byte[]>`. It does not deserialize and pretty-print the file on each request.

JSONB remains useful for the plan's queryable manifest. For a downloadable artifact with an advertised whole-file digest, reformatting JSON would change the bytes while potentially retaining the same logical content. Storing the issued representation avoids that ambiguity.

The response has `Content-Length`, `Content-Disposition: attachment`, `Cache-Control: no-store` and `X-Content-SHA256`. The filename is constructed from a UUID. This export contains sensitive artifact bodies and is protected by the operator boundary; it is not a public payment endpoint.

**Alternatives:** generating the export anew for every download would conflate preservation time with current database state. Storing only parsed JSONB and reserializing it would make the original issued byte sequence harder to guarantee. External object storage could provide a stronger independent preservation boundary, but requires credentials, storage policies, integrity verification and failure handling that this local learning milestone does not implement.

## 6. Bounded rows need bounded bytes too

The existing combined 1,000-artifact cap remains. `ProjectionPurgePlanRepository` shares one bounded evidence query between fingerprinting and full-row export, preventing the two paths from choosing different artifact classes.

Before transferring bodies to the application, `exportRecords` checks the sum of raw UTF-8 body lengths. More than 8 MiB is refused. After constructing the envelope, `encode` checks its actual serialized UTF-8 length against the same **8 MiB** limit. No partial copy is stored on refusal.

**Example:** a raw body containing many backslashes may fit under the raw-byte bound but expand when encoded as a string in the outer JSON document. It can exceed the final-file limit. The integration tests cover both raw overflow and escaped-serialization overflow.

The SQL checks the cap before sending full bodies, but PostgreSQL still performs row serialization to measure them. The application also allocates intermediate strings and bytes. These guards bound the admitted data, not peak process memory, all query cost, request concurrency or HTTP latency. Transactions retain a 30-second timeout. The number of saved copies/approvals and total storage are not globally capped; no rate limit or storage quota was added.

## 7. Creation owns a fresh fenced transaction

`ProjectionPreservationService.preserve` owns new Read Committed work, validates single-line attribution, acquires `ProjectionRoutingRepository.fence`, checks exact accepted-request replay and then evaluates the saved plan's current conditions.

The command's explicit expected revision must equal the plan's recorded revision. The existing plan review checks current route revision, review policy, target lifecycle/retirement evidence and artifact manifest. The copy is then collected and verified against that exact plan while the existing advisory/table fence excludes participating writers.

The shared transaction helpers in `ProjectionPurgePlanService` reuse the current transaction. Calling its public `REQUIRES_NEW` report methods from inside a preservation operation would create a second snapshot/connection rather than express that ownership directly. The public Milestone 13 endpoints retain their existing transaction contracts.

**Alternatives:** an unlocked report followed by a later export could combine a valid old plan with changed bodies. Reusing an old caller snapshot could miss recently committed drift. Blocking locks could queue maintenance indefinitely. The nonwaiting fence is coarse and can briefly exclude unrelated writes; preserving this established lock protocol is preferable here to introducing an unproven second one. A busy fence returns a conflict without a partial copy or approval.

## 8. Approval binds explicit human intent to one copy

`POST .../purge-plans/{planId}/approvals` accepts a request UUID, preservation UUID, expected revision, exact lowercase export SHA-256, `preservationAcknowledged: true` and a reason. The actor is always taken from the authenticated principal.

Acknowledgement must be a JSON boolean. Missing/null/false, numeric values and the string `"true"` are rejected. `StrictAcknowledgementDeserializer` prevents Jackson coercion from turning a differently typed input into approval. The existing strict integral revision deserializer is reused.

New approval takes the same fresh fence, rechecks the plan, confirms that the preservation belongs to that plan and digest, verifies the complete saved copy and inserts an immutable audit record. A composite foreign key also prevents an approval from being linked to a different plan/copy/digest combination.

Its assessment is `PRESERVATION_REVIEW_APPROVED_NO_DELETION_AUTHORITY`. The acknowledgement records that the operator accepted responsibility for preservation review. It does not establish that a download was saved off-site, that external storage is durable or that deletion is allowed. The same configured operator may create and approve; two-person separation and a general identity-management system were not introduced.

**Alternatives:** approving only a plan UUID would not say which copied bytes were reviewed. A checkbox without a digest could bind intent to the wrong file. Automatic approval after export would eliminate the deliberate operator decision. Enforcing separate people would require a broader identity/role policy than the current single-account operator boundary supports.

## 9. Immutable history and current validity are different

Both preservation and approval commands use exact request replay. UUID reuse must retain the original plan, revision, actor, reason and, for approval, copy/digest. A changed intent returns 409. Exact accepted retries are recognized before today's new-work freshness checks.

Preservation retry returns its original metadata and verifies the saved copy; a corrupted copy can therefore prevent that retry. Approval retry returns its original historical audit. It does not reapprove today's state or bypass a future executor's checks.

**Example:** preservation and approval were accepted at route revision 3. A new target is promoted at revision 4. Retrying either original command recovers its original result. New preservation/approval for the stale plan is refused. The old bundle remains downloadable if its integrity still verifies, and approval review reports `ROUTE_REVISION_CHANGED`.

`GET .../approvals/{requestId}/review` owns a new read-only Repeatable Read snapshot. It combines the existing plan blockers with stored-copy integrity/binding checks. No blockers gives `CURRENT_APPROVAL_REVIEW_ONLY`; otherwise it returns `STALE_APPROVAL_REPLAN_REQUIRED`. Both have `deletionPermitted=false`. Concurrent drift can commit while the report retains its earlier snapshot; the next report sees it.

Neither review certifies complete event history, reruns source/baseline reconciliation, evaluates all recovery gaps or proves external preservation. A future retention executor needs its own fresh safety protocol. Approval revocation/expiry and independent preservation verification remain future work.

## 10. Schema and security boundaries

V14 adds `projection_preservations` and `projection_preservation_approvals`; V1–V13 remain unchanged. Constraints enforce row/byte bounds, format/envelope identity, whole-file digest, plan/copy links and acknowledgement. Insert guards require the planned current route revision. Update/delete guards keep both records immutable.

The service performs the full manifest and freshness verification; SQL constraints alone do not authenticate the actor or prove every copied row belongs to the original inventory. Normal application writes use the shared fence. Privileged direct SQL, disabled triggers and schema administration remain outside that protocol.

All new endpoints sit under `/api/v1/operations/projection-routing`, reusing Basic/BCrypt configuration, `PROJECTION_OPERATOR`, POST CSRF/session protection and deny-all behavior when credentials are absent. GETs require the operator too. Saved-copy/approval reads validate the original plan's configured source/topic scope. No payload-bearing metadata is made available through the public payment API. Existing four-action routing metrics retain their original scope; new counters/exporters were not added.

## 11. Actual flow and important files

```text
POST /api/v1/operations/projection-routing/purge-plans/{planId}/preservations
→ ProjectionPreservationController.preserve
→ ProjectionPreservationService.preserve
→ ProjectionRoutingRepository.fence
→ ProjectionPurgePlanService.reviewInTransaction
→ ProjectionPurgePlanRepository.exportRecords
→ ProjectionPreservationVerifier.encode / verify
→ ProjectionPreservationRepository.insert
→ PostgreSQL
```

Download uses `ProjectionPreservationService.download` → `verify` → exact saved bytes. Approval uses `ProjectionPreservationService.approve` → fresh plan/copy checks → `ProjectionPreservationRepository.approve`.

| File | Purpose |
| --- | --- |
| `ProjectionPreservation.java` | Commands, versioned bundle, metadata, approval, review and download DTOs. |
| `ProjectionPreservationController.java` | Protected create, download, approval and review HTTP endpoints. |
| `ProjectionPreservationService.java` | Scope, freshness, exact retries and durable operator intent. |
| `ProjectionPreservationVerifier.java` | File/header/manifest/row integrity and final byte budget. |
| `ProjectionPreservationRepository.java` | Immutable copy and approval persistence. |
| `ProjectionPurgePlanRepository.java` | Shared capped inventory and raw-byte-checked body selection. |
| `StrictAcknowledgementDeserializer.java` | Explicit boolean acknowledgement without coercion. |
| `V14__add_projection_preservation_and_approval.sql` | Tables, constraints, cross-links and immutable guards. |

## 12. Verification, limits and next implementation

The [summary](MILESTONE_14_SUMMARY.md) records the final full-run totals. Tests cover full row/payload fidelity, Unicode byte counts, file and manifest tampering, raw/serialized byte overflow, immutable records, no changes to other application tables, stale approval refusal, historical retry, scope isolation, late insert rollback, lock contention, a read-only concurrent snapshot, an older caller snapshot and V13 upgrade with accepted-plan replay followed by preservation/approval. The controller checks roles, CSRF, trusted actors, strict input types and exact download headers/bytes.

Preservation adds a duplicate copy with sensitive bodies to the same database. It does not reclaim storage. Downloading a separate copy is possible; external durability, encryption-at-rest policy, independent signature/attestation, restore drills, two-person approval, revocation/expiry and physical purge remain unimplemented. The long-standing provider, merchant-security and production-readiness limitations also remain.

**Next logical milestone:** approval revocation/expiry and independently verifiable external-preservation acknowledgements or restore verification, before designing a bounded deletion executor. Any later executor must retain identities/audit/replay semantics, define eligible copied data precisely and handle crash/retry/concurrent changes without simply disabling existing protection triggers.

## TOPICS TO LEARN FROM THIS SESSION

- Evidence manifests and preservation boundaries
- Whole-file versus per-record integrity
- SHA-256, UTF-8 and canonical representations
- JSONB versus exact serialized TEXT
- Raw versus serialized byte budgets
- Immutable audit and composite foreign keys
- Explicit acknowledgement and strict JSON types
- Idempotent commands and historical replay
- Read Committed, Repeatable Read and REQUIRES_NEW
- Advisory locks and transaction ownership
- Authentication, authorization and CSRF
- Approval validity versus deletion authority
- Migration, tamper and concurrency testing
