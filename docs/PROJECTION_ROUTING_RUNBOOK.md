# Projection routing runbook — Milestones 10 through 12

**Current continuation (2026-10-25):** [Milestone 16](MILESTONE_16_LEARNING_GUIDE.md) adds merchant authentication, immutable payment ownership, tenant-scoped retries and financial/event reads. Payment HTTP endpoints now require merchant credentials and POST CSRF; older public-API descriptions are historical. Use the [merchant runbook](MERCHANT_ACCESS_RUNBOOK.md) and [bounded completion roadmap](ROADMAP.md).

**Current continuation (2026-10-25):** [Milestone 15](MILESTONE_15_LEARNING_GUIDE.md) adds fixed approval expiry, immutable authenticated withdrawal and independent downloaded-file verification. Follow the [lifecycle/verification runbook](PROJECTION_APPROVAL_LIFECYCLE_RUNBOOK.md). Earlier milestone descriptions retain their historical scope; deletion remains unavailable.

**Current continuation (2026-10-24):** [Milestone 14](MILESTONE_14_LEARNING_GUIDE.md) adds verified full-body preservation exports and authenticated preservation-review approval. Deletion remains unavailable. See the [preservation runbook](PROJECTION_PRESERVATION_RUNBOOK.md); earlier milestone scope/next-work descriptions below are historical.

**Current continuation (2026-10-24):** [Milestone 13](MILESTONE_13_LEARNING_GUIDE.md) adds audited bounded purge plans and fresh review. Plans retain all data and grant no deletion permission; see the [plan runbook](PROJECTION_PURGE_PLAN_RUNBOOK.md). Earlier milestone descriptions below retain their historical scope.

This HTTP surface promotes a completed shadow rebuild or returns reads to the maintained baseline. Use the [shadow rebuild runbook](SHADOW_PROJECTION_REBUILD_RUNBOOK.md) to prepare a target and the [learning guide](MILESTONE_10_LEARNING_GUIDE.md) to understand the transaction and security choices.

Routing is database-wide for this payment projection. The baseline `payment-projection-v1` stays permanent and **one shadow at a time** can receive live maintenance. Milestone 11 adds checked replacement/retirement; retired targets retain their evidence and cannot be re-enrolled. No command changes payment/ledger facts, replays a financial transition, publishes an event or changes Kafka offsets. Read the [lifecycle guide](MILESTONE_11_LEARNING_GUIDE.md) for concepts and tradeoffs.

## 1. Configure the servlet operator boundary

Set both application environment variables before starting the HTTP app:

| Variable | Value |
| --- | --- |
| `FINCORE_OPERATIONS_USERNAME` | A nonblank, single-line username of at most 100 characters, without surrounding whitespace or `:`. |
| `FINCORE_OPERATIONS_PASSWORD_HASH` | The raw BCrypt verifier for the operator password, beginning `$2a$`, `$2b$` or `$2y$`. Use a correctly generated hash; do not use plaintext or a `{bcrypt}` prefix. |

Neither configured means this surface denies access. Partial/invalid configuration fails servlet startup. The headless operations CLI remains local and does not load this servlet security configuration. Payment endpoints now use their separate merchant authentication and ownership boundary; operator credentials do not grant merchant access.

The application does not supply a default operator password or an HTTP hash-generation endpoint. Generate the verifier with a trusted BCrypt tool such as Spring Security's `BCryptPasswordEncoder.encode`, and store credentials outside source control. Authentication uses the original password, not the configured hash. Use HTTPS outside loopback; Basic credentials are not transport encryption.

## 2. Read status and establish the CSRF session

Endpoints:

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/v1/operations/projection-routing` | Current route revision, active consumer, enrolled run/shadow. |
| GET | `/api/v1/operations/projection-routing/csrf` | CSRF token/header metadata; retain its session cookie. |
| POST | `/api/v1/operations/projection-routing/promote` | Checked first enrollment or re-promotion of the same maintained shadow. |
| POST | `/api/v1/operations/projection-routing/rollback` | Checked switch back to baseline. |
| POST | `/api/v1/operations/projection-routing/replace` | From baseline, checked atomic replacement and selection of a new isolated target. |
| POST | `/api/v1/operations/projection-routing/retire` | From baseline, release the current shadow while retaining all target evidence. |
| GET | `/api/v1/operations/projection-routing/targets/{runId}` | Configured-scope target metadata and `ISOLATED`, `MAINTAINED` or `RETIRED` lifecycle. |
| GET | `/api/v1/operations/projection-routing/evidence` | Read-only, scoped target inventory and retention-review states; no deletion authority. |
| GET | `/api/v1/operations/projection-routing/operating-limits` | Configured comparison/review limits and process-local HTTP command measurements. |

All require the configured authenticated operator. POST also requires a valid CSRF token and its matching session cookie. A PowerShell client can prompt for the original credential without embedding it in history:

```powershell
$routingBase = 'http://localhost:8080/api/v1/operations/projection-routing'
$routingCredential = Get-Credential -Message 'Configured projection operator'
$routingBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $routingCredential.UserName + ':' + $routingCredential.GetNetworkCredential().Password))
$routingHeaders = @{ Authorization = 'Basic ' + $routingBasic }
$routingSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$routingState = Invoke-RestMethod -Uri $routingBase -Headers $routingHeaders `
    -WebSession $routingSession
$routingCsrf = Invoke-RestMethod -Uri "$routingBase/csrf" -Headers $routingHeaders `
    -WebSession $routingSession
$routingHeaders[$routingCsrf.headerName] = $routingCsrf.token
$routingState
```

The session and headers contain sensitive state; keep them within the operator session. If the token/session expires or the app restarts, fetch a new token and cookie. Fresh installations report revision 0 with `activeConsumer=payment-projection-v1` and no enrolled shadow. Route status identifies current ownership; it is not a readiness certificate.

## 3. Prepare a first promotion

Before enrollment, complete `rebuild-create` → repeated bounded `rebuild-step` → `rebuild-validate` / `rebuild-catch-up` as needed. Keep the run UUID and its current generation. Source namespace and topic must match the app handling routing.

First promotion requires a frozen `BUILT` generation with no pending/blocked items and agreement with current source. The baseline and shadow must both match each current source payment's retained outbox event and canonical receipt, and their latest rows/envelopes must agree with each other, including source-less consumer records. Open failures/quarantines in any source scope for the logical consumer block a switch. Historical completeness remains `NOT_CERTIFIED`.

Both views must already be healthy/current/equivalent. This cutover workflow cannot repair a missing or drifted baseline. That repair needs a separate audited workflow; the switch checks protect existing reads and verified rollback.

Let the normal consumer catch up and address observed incidents. `LOCAL` inbox delivery alone does not populate projections. A source-less baseline record absent from the source-backed build remains a blocker; this API does not offer arbitrary replacement/copying or force promotion.

Use the observed revision and generation explicitly:

```powershell
$routingRunId = 'REPLACE-WITH-BUILT-RUN-UUID'
$routingBuildGeneration = 0 # Replace with rebuild-status generation.
$routingPromotion = @{
    requestId = [guid]::NewGuid().ToString()
    runId = $routingRunId
    expectedBuildGeneration = $routingBuildGeneration
    expectedRevision = $routingState.revision
    reason = 'Promote the reviewed current payment projection'
}
$routingPromotionJson = $routingPromotion | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post -Uri "$routingBase/promote" -Headers $routingHeaders `
    -WebSession $routingSession -ContentType 'application/json' -Body $routingPromotionJson
```

There is no `operator`/`actor` field in the command. The accepted audit actor is the authenticated principal. Reasons must be nonblank single lines, at most 500 characters, without control or Unicode line/paragraph separators. IDs must be UUIDs. HTTP revisions/generations require explicit nonnegative integral JSON numbers within their supported range; omitted/null counters, numeric strings, decimals and overflow are rejected rather than coerced.

The service owns a `REQUIRES_NEW` / Read Committed transaction with a 30-second timeout, acquires a nonwaiting exclusive fence and table freeze, rechecks readiness inside that transaction and commits audit/enrollment/pointer together. It suspends any caller transaction so an old Repeatable Read snapshot cannot govern the switch. A later caller rollback does not undo an accepted independent route decision; caller-held conflicting locks can cause a busy refusal. An older `rebuild-validate` response is not reused as authority. On success the result contains request ID, action, accepted revision, active consumer, actor, reason, time and `replayed=false`.

## 4. Verify reads and ongoing ownership

```powershell
$routingState = Invoke-RestMethod -Uri $routingBase -Headers $routingHeaders `
    -WebSession $routingSession
$routingState
# Use a real payment UUID:
# Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/payments/PAYMENT-UUID/projection'
```

The projection GET selects its route and data in one SQL statement. Baseline remains the permanent logical identity for normal diagnostics, reconciliation, quarantine and replay. Enrollment keeps both baseline and shadow warm in a single consumer transaction, including baseline duplicate events. A target discrepancy aborts that transaction and uses existing event-conflict handling; it is not silently repaired.

Live consumption keeps `REQUIRED` propagation to join position/success resolution atomically. It checks the actual JDBC connection for Read Committed before receipt/projection writes; an inherited Repeatable Read/Serializable caller is rejected. Integrations must not wrap this consumer in an older-snapshot transaction or change propagation to `REQUIRES_NEW` to bypass that guard.

Source writes still precede asynchronous consumption, so the routed projection may temporarily lag the payment. Both targets receive the same next valid consumed event; selecting a shadow does not add synchronous payment-to-projection updates.

An ever-enrolled run cannot accept further rebuild steps/catch-up mutation, including after retirement. Its frozen manifest stays historical while its maintained projection advances. Later rebuild validation may therefore show drift from that historical manifest; `promotion=ENROLLED_LIVE_FANOUT` identifies current maintenance, while `RETIRED_EVIDENCE_RETAINED` identifies a retired target. Create a fresh isolated run for replacement and finish/catch up its manifest before submitting it.

## 5. Roll back or re-promote

Fetch current status before a **new** decision and create a fresh request UUID:

```powershell
$routingState = Invoke-RestMethod -Uri $routingBase -Headers $routingHeaders `
    -WebSession $routingSession
$routingRollback = @{
    requestId = [guid]::NewGuid().ToString()
    expectedRevision = $routingState.revision
    reason = 'Return projection reads to the maintained baseline'
}
$routingRollbackJson = $routingRollback | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post -Uri "$routingBase/rollback" -Headers $routingHeaders `
    -WebSession $routingSession -ContentType 'application/json' -Body $routingRollbackJson
```

Rollback requires the shadow to be active, the current revision and the same fresh data/gap checks. It retains enrollment and fanout, so returning to the baseline does not undo newer consumed facts. Re-promotion uses `/promote`, the **same maintained run and frozen build generation**, a fresh request UUID and the then-current revision. Selecting a different run through `/promote` while a shadow remains maintained is rejected; use the explicit replacement workflow.

## 6. Replace the maintained shadow

First use `/rollback` if the shadow serves reads. Build a **new, never-enrolled** candidate with the rebuild CLI. Complete its current generation and catch it up as needed; both baseline and old shadow must remain current/equivalent too. Fetch route status immediately before constructing a new command.

```powershell
$routingState = Invoke-RestMethod -Uri $routingBase -Headers $routingHeaders -WebSession $routingSession
$routingReplacement = @{
    requestId = [guid]::NewGuid().ToString()
    expectedPreviousRunId = $routingState.shadowRunId
    runId = 'REPLACE-WITH-NEW-BUILT-RUN-UUID'
    expectedBuildGeneration = 0 # Use the candidate's actual current generation.
    expectedRevision = $routingState.revision
    reason = 'Replace the maintained target with the reviewed current candidate'
}
$routingReplacementJson = $routingReplacement | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$routingBase/replace" -Headers $routingHeaders `
    -WebSession $routingSession -ContentType 'application/json' -Body $routingReplacementJson
```

Replacement activates the new shadow and transfers maintenance atomically; it retires the previous one. The old target receives no subsequent consumer updates. Its receipts, final projections, manifests and audit history remain available. The new run's build metadata is permanently frozen. No second warm shadow is added. Use `/rollback` to return reads to baseline and `/promote` to select the same new maintained shadow again.

Current source drift, candidate generation mismatch, source-less baseline records absent from the candidate, old/new target disagreement, open gaps or active writers can cause 409. Inspect and resolve the cause; do not force enrollment by changing stored receipts. V11 preserves V10 decisions and backfills their previous-target metadata automatically during normal Flyway startup.

Deploy the Milestone 11 application with V11 before using lifecycle commands. Older Milestone 10 binaries do not implement the nullable maintenance pointer or new audit actions. Mixed-version rolling operation and schema downgrade have not been validated.

## 7. Retire maintenance without replacing it

After explicit baseline rollback, obtain current status and name the maintained run:

```powershell
$routingState = Invoke-RestMethod -Uri $routingBase -Headers $routingHeaders -WebSession $routingSession
$routingRetirement = @{
    requestId = [guid]::NewGuid().ToString()
    runId = $routingState.shadowRunId
    expectedRevision = $routingState.revision
    reason = 'Release shadow maintenance and retain its evidence'
}
$routingRetirementJson = $routingRetirement | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$routingBase/retire" -Headers $routingHeaders `
    -WebSession $routingSession -ContentType 'application/json' -Body $routingRetirementJson
Invoke-RestMethod -Uri "$routingBase/targets/$($routingRetirement.runId)" `
    -Headers $routingHeaders -WebSession $routingSession
```

Retirement requires the same current source/target agreement and absence of tracked gaps. It leaves baseline reads active, advances the revision and clears the maintenance pointer. Subsequent consumption writes only baseline. Retired data is retained and cannot be re-enrolled or rebuilt under the same identity. A fresh isolated run can later be promoted using the current nonzero revision. Retirement of isolated builds, data deletion, retention duration and cleanup scheduling are not provided.

## 8. Handle retries and refusals

| Observation | Operator action |
| --- | --- |
| 401 | Supply the configured Basic username and original password. |
| 403 | Check configured access/role and, for POST, fetch a fresh CSRF token with its session cookie. |
| 400 | Correct invalid command structure/bounds; do not guess that it was accepted. |
| 404 `PROJECTION_REBUILD_NOT_FOUND` | Confirm run identity and the app's configured source/topic. |
| 409 `PROJECTION_COMPARISON_BUDGET_EXCEEDED` | Inspect configured per-dataset limits and operating capacity before submitting oversized new routing work. |
| 409 `PROJECTION_ROUTING_CONFLICT` | Inspect current route, rebuild/source freshness, tracked incidents and active work. Resolve contention or data disagreement before deciding to retry or submit new intent. |
| Successful response lost | Retry the exact serialized body with the same request UUID and authenticated actor, retaining original expected values. |

An exact accepted replay returns the original result with `replayed=true`, even after later route changes. It performs no new switch. Read status separately for current routing. Reusing a request UUID with a changed actor, reason, action or precondition conflicts. Replay still acquires the fence and can receive a temporary busy 409.

Fresh switches use a new request UUID/current revision. Do not regenerate the request ID merely because a response was lost. Do not edit audit/route/manifest rows, delete receipts or use privileged TRUNCATE to force readiness; those are outside the supported application workflow.

The fence/table checks are conservative and database-wide. An active consumer, source writer, rebuild or diagnostic writer can make a command busy. There is no automatic retry job or guarantee that a constantly busy database will eventually permit a switch; schedule a suitable operational window. Production-volume performance, multi-instance availability and complete historical recovery are not certified by this block.

## 9. Review retained evidence and comparison capacity

Milestone 12 applies a validated per-dataset row budget to every new promote/rollback/replace/retire after acquiring the fence and before larger comparisons. The default is 100000 rows for each source-payment, baseline/selected-projection and selected-current-manifest dataset. An over-budget refusal writes no route, audit or enrollment. Accepted historical replay is recognized before these new-work checks and remains subject to the existing fence.

The new [evidence and operating-limits runbook](PROJECTION_EVIDENCE_RUNBOOK.md) covers configuration, authenticated GET requests, page interpretation and measurement scope. `REVIEW_RETIRED_EVIDENCE` is not permission to delete: every target reports `deletionPermitted=false`, and existing evidence protections remain in place. Counters cover this process's HTTP command invocations through service transaction completion, not the entire cluster or all direct service/CLI calls.
