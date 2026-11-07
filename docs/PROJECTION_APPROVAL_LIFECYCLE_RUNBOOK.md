# Projection approval lifecycle and offline verification runbook — Milestone 15

Use the [learning guide](MILESTONE_15_LEARNING_GUIDE.md) for rationale and [summary](MILESTONE_15_SUMMARY.md) for validation. Continue the [preservation workflow](PROJECTION_PRESERVATION_RUNBOOK.md) using the existing [operator credentials, session and CSRF setup](PROJECTION_ROUTING_RUNBOOK.md#1-configure-the-servlet-operator-boundary).

All HTTP paths below have prefix `/api/v1/operations/projection-routing`. They require `PROJECTION_OPERATOR`; POST requires the configured session's CSRF token. Actor is taken from the authenticated principal. Every approval, review, revocation and file-verification result retains `deletionPermitted=false`.

## 1. Set the lifetime for new approvals

```powershell
$env:FINCORE_PROJECTION_APPROVAL_VALIDITY_MINUTES = '60'
```

Default 60; valid integers 1–1440. Restart the application to apply configuration. New approvals snapshot `approvedAt`, `validityMinutes` and `expiresAt`; changes do not alter accepted intervals. Metadata GET and exact accepted retries retain those values even after expiry or revocation.

V14 approvals retain their original fields and have null validity fields after migration. Review flags `APPROVAL_VALIDITY_UNSPECIFIED`. Create a new approval request UUID after fresh plan/copy checks if another approval is appropriate. Exact retry is not renewal.

## 2. Observe current validity

GET `/approvals/{approvalId}/review`. In addition to plan/copy blockers:

| Blocker | Meaning and response |
| --- | --- |
| `APPROVAL_VALIDITY_UNSPECIFIED` | Legacy interval is unknown; do not infer one from today's setting. |
| `APPROVAL_TIME_UNCERTAIN` | Database observation precedes acceptance; investigate time. |
| `APPROVAL_EXPIRED` | Observation is equal to or after stored expiry. New intent needs a new request UUID and current checks. |
| `APPROVAL_REVOKED` | This approval has an immutable withdrawal. Inspect its revocation; there is no unrevoke. |

`CURRENT_APPROVAL_REVIEW_ONLY` describes one snapshot, grants no deletion capability and does not certify external durability or complete history. A concurrent withdrawal can be seen by the next observation. A metadata GET or replay is historical retrieval, not this review.

## 3. Withdraw an approval

POST `/approvals/{approvalId}/revoke`:

```json
{
  "requestId": "33333333-3333-4333-8333-333333333333",
  "reason": "Withdraw this approval pending independent preservation checks"
}
```

Use an unused UUID and a nonblank single-line reason of at most 500 characters. No expected route revision, digest or actor field is required. HTTP 200 returns `{ "revocation": ..., "replayed": false }`; the audit includes request/approval IDs, database timestamp, principal actor, reason and `REVOKED_NO_DELETION_AUTHORITY`.

GET `/approvals/{approvalId}/revocation` returns that audit. If no revocation exists, GET returns 404. Exact POST retry with the same request/approval/actor/reason returns the original with `replayed=true`. Changed intent or another request revoking the same approval returns 409. One approval's withdrawal leaves other approvals and its plan/copy intact.

Withdrawal can succeed for an expired, stale, legacy or corrupt-copy approval as long as its identity and configured source/topic are valid. It uses its own nonwaiting lock. Busy revocation lock returns 409 `PROJECTION_PURGE_PLAN_CONFLICT`; retry the exact intent after contention clears. It does not require a healthy/current route or load the copy body.

## 4. Retain three verification inputs

Obtain and retain these through the protected API using the approved operator session:

1. Exact bundle bytes: GET `/preservations/{preservationId}/export` → `projection-preservation.json`.
2. Trusted metadata object: GET `/preservations/{preservationId}` → `preservation-metadata.json`.
3. Trusted original plan object: GET `/purge-plans/{planId}` → `original-plan.json`.

Save the bundle bytes directly. Reformatting, transcoding or adding a newline changes its expected hash. Save metadata/plan as UTF-8 JSON objects without the POST result wrappers. Retain those reference values through a trusted process independent of the potentially damaged/untrusted bundle. Merely extracting the plan from that bundle does not establish trust. The API does not implement independent reference custody or signatures.

The bundle contains sensitive full artifact bodies. Its cap is 8 MiB; each CLI reference file is capped at 1 MiB. Format and plan version 1 are supported. Do not change fields to make a failing file pass.

## 5. Run the independent verifier

Build with Java 21 (`.\mvnw.cmd package` on Windows), then run from the project directory:

```powershell
java -jar target/fincore-payments-0.0.1-SNAPSHOT.jar verify-preservation `
  '--bundle=projection-preservation.json' `
  '--metadata=preservation-metadata.json' `
  '--plan=original-plan.json'
$LASTEXITCODE
```

Quote an entire option if its path contains spaces. `verify-preservation` must be the first application argument. Exactly the three unique `--name=PATH` options are accepted; there is no URL, download, upload, repair or approval option. This dispatch starts no Spring context and requires no database/Kafka credentials or running services.

Success: exit 0 and one JSON report with `status=VERIFIED_OFFLINE_NO_DELETION_AUTHORITY`, plan/copy UUIDs, SHA-256, byte and artifact counts, `databaseContacted=false`, `deletionPermitted=false`.

Refusal: exit 2 and generic `PRESERVATION_FILE_VERIFICATION_FAILED` JSON. Check existence/permissions, size, strict UTF-8/versioned JSON, expected references and exact bytes in your trusted local environment. The output intentionally omits paths, payloads, attribution and stack traces. A new file from the server is useful only after investigating trust/integrity; do not overwrite the evidence of failure blindly.

## 6. Interpret the result correctly

A pass establishes agreement of the supplied bytes and trusted reference values at the time they were read. It does not prove storage redundancy, future readability, independent authenticity, restore success, current approval validity or deletion permission. Replacing all three inputs can produce a self-consistent set; secure reference provenance matters.

The server does not ingest or authenticate the console report. There is no durable external-verification receipt, signature, external object-store integration, restore/import API, two-person policy or physical purge executor. Keep using fresh server reviews for current approval status and retain the independent storage/restore obligations.
