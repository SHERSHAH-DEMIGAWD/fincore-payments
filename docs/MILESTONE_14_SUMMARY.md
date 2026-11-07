# Milestone 14 — Verified preservation exports and authenticated review approval

Implemented 2026-10-24. [Learning guide](MILESTONE_14_LEARNING_GUIDE.md) · [Operator runbook](PROJECTION_PRESERVATION_RUNBOOK.md).

## 1. What was implemented

- Saved complete, versioned preservation bundles for current retired-target plans.
- Verified each copied record against its planned identity/hash and the exact exported file against SHA-256/byte length.
- Authenticated exact-byte downloads with attachment, digest and no-store headers.
- Immutable preservation-review approval bound to one plan/copy/digest and explicit acknowledgement.
- Exact command replay, fresh approval review and stale/corrupt-copy refusal.
- V14 integrity constraints, copy/approval links and immutable guards; deletion remains unavailable.

## 2. Concepts / technologies used

- Evidence manifests / SHA-256 — complete row and whole-file verification.
- UTF-8 / exact serialized TEXT — stable downloadable bytes and size limits.
- Strict JSON deserialization — explicit boolean acknowledgement and integral revisions.
- Idempotency / transaction isolation / advisory fencing — retries, freshness and atomic writes.
- Spring Security / CSRF — existing operator boundary reused.
- Flyway / composite foreign keys / triggers — durable immutable preservation and approval evidence.
- JUnit / Mockito / PostgreSQL integration testing — corruption, concurrency, budgets and upgrade behavior.

## 3. Important files

- `ProjectionPreservationController.java` — create, download, approve and review endpoints.
- `ProjectionPreservationService.java` — current-plan checks, scope, exact retries and attributed decisions.
- `ProjectionPreservationVerifier.java` — file/header/manifest/row verification and export byte bound.
- `ProjectionPreservationRepository.java` — stores immutable copies and approvals.
- `ProjectionPreservation.java` — commands, versioned bundles and response DTOs.
- `ProjectionPurgePlanRepository.java` / `ProjectionPurgePlanService.java` — shared inventory and in-transaction freshness checks.
- `StrictAcknowledgementDeserializer.java` — rejects coerced approval acknowledgements.
- `V14__add_projection_preservation_and_approval.sql` — adds integrity/link/immutability constraints.
- New database/MVC/verifier tests and learning/runbook files — verification and teaching handoff.

## 4. Request flow

```text
POST /api/v1/operations/projection-routing/purge-plans/{planId}/approvals
→ ProjectionPreservationController.approve
→ ProjectionPreservationService.approve
→ fresh fenced plan checks + ProjectionPreservationVerifier.verify
→ ProjectionPreservationRepository.approve
→ PostgreSQL
→ ProjectionPreservation.ApprovalResult
```

## 5. Important design decisions

- Complete copies retain every planned record; hashes alone cannot reconstruct evidence.
- Raw bodies and final UTF-8 bundles each have an 8 MiB limit, in addition to 1,000 records.
- The exact issued JSON is stored as TEXT and downloaded without reserialization.
- Approval binds the exact copy/digest and records a strict, explicit operator acknowledgement.
- Historical acceptance remains separate from current validity; every response retains `deletionPermitted=false`.
- Same-database copies and human acknowledgements do not prove independent backup durability.

## 6. Test status

Final Java 21 full Maven `verify`: **BUILD SUCCESS — 704 executed checks passed, zero failures/errors**.

| Group | Passed | Skipped |
| --- | ---: | ---: |
| Unit / MVC / service / configuration / CLI | 404 | 0 |
| Real PostgreSQL | 288 | 0 |
| Real Kafka + PostgreSQL | 7 | 0 |
| Packaged CLI | 5 | 0 |
| Docker-dependent | 0 | 297 |

This adds 35 unit/MVC/verifier/security invocations and 19 real PostgreSQL cases over Milestone 13. Totals come from one final full run, not summed attempts. Fresh V1–V14 startup, legacy upgrade, V10 routing-history upgrade/replay, V12 plan-schema upgrade and V13→V14 accepted-plan replay followed by preservation/approval passed. Evidence: ignored `.tools/milestone14-verify.log` and independently aggregated `.tools/milestone14-qa.json`.

Actual local environment: PostgreSQL 18.6 / Kafka broker 3.9.1. Docker was unavailable, so PostgreSQL 17.6 containers remain unverified; Flyway warns that PostgreSQL 18 is above its tested version. Production load, multi-broker availability, mixed-version operation, independent external-backup durability and restore/physical purge were not tested. The resumed session recovered stopped test services and used fresh broker data after an earlier Windows file-access error; no application assertions or behavior were weakened for that issue.

Coverage includes row/payload fidelity, Unicode byte counts, file/header/manifest tampering, raw/serialized overflow, scope isolation, exact retries after routing changes, stale approval refusal, unchanged other application tables, late-write rollback, immutable guards, lock contention, caller/review snapshots and V13 upgrade with accepted-plan replay.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–14, including verified preservation export and authenticated preservation-review approval.

**CURRENTLY POSSIBLE:** ✓ Save a verified bounded copy, download exact bytes, acknowledge/review it and recheck approval freshness.

**NOT IMPLEMENTED YET:** ✗ Independent external-preservation verification, restore/import, approval revocation/expiry, two-person policy, physical purge, real providers/settlement or production readiness.

**NEXT LOGICAL MILESTONE:** approval revocation/expiry and independently verifiable external preservation or restore checks before designing a bounded deletion executor.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Preservation manifests and backup boundaries
- Per-record versus whole-file integrity
- SHA-256 and UTF-8
- JSONB versus exact serialized TEXT
- Raw versus serialized byte limits
- Strict JSON acknowledgements
- Immutable audit and composite foreign keys
- Idempotency and historical replay
- Transaction isolation and fencing
- Approval validity and deletion authority
- Migration, tamper and concurrency testing
