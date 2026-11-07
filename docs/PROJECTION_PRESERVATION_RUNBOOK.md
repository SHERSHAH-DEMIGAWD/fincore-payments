# Projection preservation and approval runbook — Milestones 14–15

**Milestone 15 continuation:** approvals now have fixed expiry and immutable withdrawal; downloaded bundles can be checked offline. Follow the [approval lifecycle and file-verification runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md) and [learning guide](MILESTONE_15_LEARNING_GUIDE.md). Every result still grants no deletion authority.

Read the [learning guide](MILESTONE_14_LEARNING_GUIDE.md) for concepts/tradeoffs and the [summary](MILESTONE_14_SUMMARY.md) for verification. This workflow saves full sensitive artifact bodies in the same PostgreSQL database and permits a separate download. It does not delete anything or prove external backup durability.

## 1. Preconditions

Use the existing [operator credentials and CSRF/session setup](PROJECTION_ROUTING_RUNBOOK.md#1-configure-the-servlet-operator-boundary). All endpoints below require `PROJECTION_OPERATOR`; POST also requires the token/session obtained from `/csrf`. Use TLS outside loopback. With credentials absent, the endpoints deny access.

Create and review a current plan using the [purge-plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). The plan must name one retired target past its configured review window and remain current. Preserve the plan UUID and its exact expected route revision. A different current revision requires a fresh plan, not a substituted revision under the old plan.

All relative paths below use prefix `/api/v1/operations/projection-routing`.

## 2. Save a verified copy

POST `/purge-plans/{planId}/preservations` with an unused request UUID:

```json
{
  "requestId": "11111111-1111-4111-8111-111111111111",
  "expectedRevision": 3,
  "reason": "Preserve the complete retired-target evidence for review"
}
```

Replace example values with the actual plan context. Expected revision must be an explicit nonnegative integral JSON number. Reasons are nonblank single lines of at most 500 characters. The authenticated principal supplies the actor.

HTTP 200 returns `{ "preservation": ..., "replayed": false }`. Metadata includes request/plan IDs, format version, revision, observation time, actor/reason, artifact count, exact export byte length, SHA-256 and `deletionPermitted=false`. `GET /preservations/{requestId}` retrieves that metadata. An exact POST retry returns the original with `replayed=true`; changed intent under that UUID conflicts.

The combined cap is 1,000 records. Both raw artifact bodies and the final serialized UTF-8 bundle must fit within 8 MiB. An overflow or stale plan returns 409 and saves no partial copy. Preservation creation remains HTTP-only; Milestone 15 adds a separate offline verification command.

## 3. Download and verify the exact file

GET `/preservations/{requestId}/export` returns the stored JSON file after server integrity verification. The response uses an attachment filename, `Cache-Control: no-store`, `Content-Length` and `X-Content-SHA256`. It contains full evidence bodies, unlike the Milestone 13 inventory metadata.

In a client using the authenticated operator session, save the response bytes directly to a file. Do not pretty-print, transcode or add a newline before checking the file hash. For a saved file:

```powershell
$actualDigest = (Get-FileHash -LiteralPath '.\projection-preservation.json' -Algorithm SHA256).Hash.ToLowerInvariant()
$actualDigest
```

Compare this lowercase value with both the trusted preservation metadata and `X-Content-SHA256`. The file length must match `exportBytes`. The bundle includes format version, original plan and record bodies with their individual digests. A record's `body` is a JSON string representing the exact PostgreSQL row text, not a rewritten application DTO.

Store and handle this sensitive file according to the chosen external preservation process. The server's saved copy and matching hash do not establish that your separate storage is durable, off-site or restorable. There is no import/restore endpoint in this milestone.

## 4. Record preservation-review approval

After verifying/reviewing the copy and accepting the preservation responsibilities, POST `/purge-plans/{planId}/approvals`:

```json
{
  "requestId": "22222222-2222-4222-8222-222222222222",
  "preservationId": "11111111-1111-4111-8111-111111111111",
  "expectedRevision": 3,
  "exportSha256": "replace-with-the-exact-64-character-lowercase-file-digest",
  "preservationAcknowledged": true,
  "reason": "Verified the exported evidence and reviewed preservation requirements"
}
```

The placeholder digest is not a valid request value; replace it with the exact lowercase hash. Acknowledgement must be JSON boolean `true`, not a string or number. Missing/null/false acknowledgement is refused. The same configured operator may preserve and approve; there is no two-person policy.

New approval reacquires the fence, rechecks plan freshness, verifies the stored copy and binds the exact preservation UUID/plan/digest. HTTP 200 returns `{ "approval": ..., "replayed": false }` with assessment `PRESERVATION_REVIEW_APPROVED_NO_DELETION_AUTHORITY`. New approvals also snapshot `validityMinutes` and `expiresAt`; default 60 minutes, configurable from 1–1440. `deletionPermitted` remains false.

GET `/approvals/{requestId}` retrieves the immutable historical decision. Exact POST retry returns the saved decision after later route changes; it is not a fresh approval of today's state. New request UUIDs represent new intent.

## 5. Recheck validity

GET `/approvals/{requestId}/review` observes one fresh read-only snapshot. `CURRENT_APPROVAL_REVIEW_ONLY` means the implemented plan and stored-copy checks agree at that observation. `STALE_APPROVAL_REPLAN_REQUIRED` includes blockers such as changed route/policy/artifacts, preservation binding changes or failed copy integrity. Neither permits deletion or certifies source/baseline/gap health or complete history.

A valid old bundle remains downloadable after live target drift if its original copy verifies. Do not overwrite a stale plan, preservation or approval. Use a fresh plan and new request UUIDs when new intent is required. Review now includes unspecified legacy validity, uncertain time, expiry and revocation blockers. See the [Milestone 15 runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md) for withdrawal and offline checks against independently trusted references.

## 6. Failures and limits

| Response | Action |
| --- | --- |
| 400 | Correct UUIDs, explicit integral revision, lowercase digest, boolean acknowledgement or attribution. |
| 401 / 403 | Check configured credentials, operator role and POST CSRF/session. |
| 404 `PROJECTION_PURGE_PLAN_NOT_FOUND` | Check plan/copy/approval ID and configured source/topic scope. |
| 409 `PROJECTION_PURGE_PLAN_CONFLICT` | Inspect stale intent, copy/digest binding, integrity or row/byte budgets. |
| 409 `PROJECTION_ROUTING_CONFLICT` | The nonwaiting fence is busy; inspect activity and retry the exact intent. |

Failed verification does not repair or silently replace evidence. SQL guards keep saved copies, approvals and revocations immutable. Financial/source/baseline/recovery data remain outside export scope and unchanged by these commands. Global storage quotas, independently attested external durability, authenticated durable verification receipts, restore drills, deletion execution and automatic cleanup remain future work. Existing `/operating-limits` counters still cover only the four routing actions.
