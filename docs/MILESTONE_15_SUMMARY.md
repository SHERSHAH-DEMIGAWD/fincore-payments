# Milestone 15 — Approval lifecycle and independent file verification

Implemented 2026-10-25. [Teaching guide](MILESTONE_15_LEARNING_GUIDE.md) · [Operator runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md).

## 1. What was implemented

- Fixed expiry and accepted-policy snapshots for new preservation-review approvals.
- Fresh-review blockers for legacy validity, uncertain time, expiry and withdrawal.
- Authenticated immutable revocation of one approval, with exact command replay.
- Retrieval of withdrawal history without changing approvals or preserved copies.
- Independent packaged-JAR verification of downloaded files against trusted metadata and original plans.
- Strict bounded UTF-8/JSON parsing and sanitized machine-readable results.
- V15 migration preserving legacy audit fields; every deletion-permission flag remains false.

## 2. Concepts / technologies used

- TTL / policy snapshots — immutable accepted validity intervals.
- Idempotency / immutable audits — historical acceptance and withdrawal.
- Spring Security / CSRF — existing principal-based operator boundary.
- Transaction isolation / advisory locks — atomic withdrawal and coherent reviews.
- Flyway / constraints / triggers — legacy-compatible validity and protected revocation storage.
- UTF-8 / strict Jackson / SHA-256 — independent exact-file and manifest verification.
- JUnit / Mockito / PostgreSQL / packaged process tests — boundaries, rollback, races and offline dispatch.

## 3. Important files

- `ProjectionApprovalLifecycleController.java` / `ProjectionApprovalLifecycleService.java` — authenticated withdrawal, scope and replay.
- `ProjectionApprovalLifecycleRepository.java` / `ProjectionApprovalLifecycle.java` — revocation persistence, lock and DTOs.
- `ProjectionApprovalProperties.java` / `application.yml` — validated approval lifetime.
- `ProjectionPreservationService.java` / `ProjectionPreservationRepository.java` — accepted expiry and fresh validity checks.
- `V15__add_approval_validity_and_revocation.sql` — additive validity and immutable revocation schema.
- `PreservationFileVerifierCli.java` / `FincorePaymentsApplication.java` — verification before Spring startup.
- Lifecycle, verifier and packaged-process test suites — integration and offline regression coverage.
- Teaching guide and lifecycle runbook — concepts, tradeoffs, trust boundaries and commands.

## 4. Request flow

```text
POST /api/v1/operations/projection-routing/approvals/{approvalId}/revoke
→ ProjectionApprovalLifecycleController.revoke
→ ProjectionApprovalLifecycleService.revoke
→ ProjectionApprovalLifecycleRepository.fence / revoke
→ PostgreSQL projection_approval_revocations
→ ProjectionApprovalLifecycle.Result
```

## 5. Important design decisions

- New approvals snapshot their duration; current configuration never rewrites accepted policy.
- Legacy validity stays unspecified rather than inventing historical acceptance.
- Revocation appends a separate immutable fact and uses its own nonwaiting lock.
- Withdrawal needs identity/scope, so stale or corrupt copies do not prevent it.
- Exact accepted retries remain historical retrieval after expiry or revocation.
- Offline verification requires independently trusted references and grants no deletion authority.

## 6. Test status

Final Java 21 Maven `verify`: **BUILD SUCCESS — 798 executed checks passed, zero failures/errors**.

| Group | Passed | Skipped |
| --- | ---: | ---: |
| Unit / MVC / service / configuration / CLI | 475 | 0 |
| Real PostgreSQL | 308 | 0 |
| Real Kafka + PostgreSQL | 7 | 0 |
| Packaged CLI | 8 | 0 |
| Docker-dependent | 0 | 317 |

Adds 71 unit/MVC/configuration/offline invocations, 20 PostgreSQL cases and three packaged offline checks over Milestone 14. Totals use the full-run suites, not added reruns. A final three-case packaged-verifier rerun passed after making its Java executable selection portable; those checks are not counted again. Focused 80-check run also passed. Fresh V1–V15, earlier upgrade regressions and V14→V15 legacy-audit/replay followed by new approval/revocation passed. Evidence: ignored `.tools/milestone15-verify.log`, `.tools/milestone15-packaged-final.log` and independently aggregated `.tools/milestone15-qa.json`.

Coverage includes expiry/clock boundaries, policy snapshots, stale/expired/corrupt-copy withdrawal, request conflicts, immutable/unique/FK guards, concurrent withdrawal, lock contention, late rollback, scope, read-only review snapshots and unchanged unrelated tables. Offline coverage includes Unicode, tampering, wrong references, strict parsing/encoding/size limits, sanitized errors and executable-JAR dispatch with unusable infrastructure/configuration.

Actual local PostgreSQL 18.6 / Kafka broker 3.9.1. Docker was unavailable: PostgreSQL 17.6 container checks skipped; Flyway warns PostgreSQL 18 exceeds tested support. Linux execution, production load, multi-broker/mixed-version behavior, external durability and restore/deletion remain unverified. An earlier usage limit prevented broker-start approval review; review succeeded on resumption. The workspace PostgreSQL cluster recovered its interrupted shutdown before testing. No assertion was weakened for these environment issues.

Cleanup: stopped the verified session Kafka process and workspace PostgreSQL cluster; PostgreSQL completed its shutdown checkpoint. No listeners remain on 55432/19092/19093. Unrelated PostgreSQL 5432 and the separate project were untouched. The tested JAR covers all final application source/resources; no commit or push was made.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–15, including approval lifecycle and independent file verification.

**CURRENTLY POSSIBLE:** ✓ Expire/review/withdraw approvals and independently verify copied evidence files.

**NOT IMPLEMENTED YET:** ✗ Authenticated durable external-verification receipts, attested storage durability, restore/import, two-person approval, purge execution, real providers/settlement or production readiness.

**NEXT LOGICAL MILESTONE:** authenticated external-verification and restore evidence, before a separately designed bounded purge executor.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Immutable audit and current validity
- TTL and policy snapshots
- Idempotent retries
- Flyway legacy compatibility
- Advisory locks and transaction isolation
- Principal identity and CSRF
- Strict JSON and UTF-8
- SHA-256 manifests and trust roots
- Executable JAR dispatch and process tests
- Integrity, durability and restore evidence
